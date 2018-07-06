/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.artofarc.esb.action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import javax.xml.namespace.QName;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xquery.XQConstants;
import javax.xml.xquery.XQItem;
import javax.xml.xquery.XQItemType;
import javax.xml.xquery.XQPreparedExpression;
import javax.xml.xquery.XQResultSequence;
import javax.xml.xquery.XQSequence;

import org.w3c.dom.Node;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;

public class TransformAction extends Action {

	private final String _xquery;
	private final List<String> _varNames;
	protected List<String> _bindNames;
	protected String _contextItem;

	@SuppressWarnings("unused")
	// not reliable
	private int _resultItemOccurrence;

	public TransformAction(String xquery) {
		this(xquery, Collections.<String> emptyList());
	}

	protected TransformAction(String xquery, List<String> varNames) {
		_xquery = xquery;
		_varNames = varNames;
	}

	public List<String> getVarNames() {
		return _varNames;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		return prepare(context, message, message.getVariables());
	}

	protected ExecutionContext prepare(Context context, ESBMessage message, Map<String, Object> destMap) throws Exception {
		context.getTimeGauge().startTimeMeasurement();
		XQPreparedExpression xqExpression = context.getXQPreparedExpression(_xquery);
		if (_bindNames == null) {
			_bindNames = new ArrayList<>();
			for (QName qName : xqExpression.getAllExternalVariables()) {
				_bindNames.add(qName.getLocalPart());
			}
			_resultItemOccurrence = xqExpression.getStaticResultType().getItemOccurrence();
		}
		context.getTimeGauge().stopTimeMeasurement("prepareExpression", true);
		if (_contextItem != null) {
			bind(_contextItem, XQConstants.CONTEXT_ITEM, xqExpression, context, message);
		} else {
			if (message.getBodyType() == BodyType.XQ_SEQUENCE) {
				XQSequence sequence = message.getBody();
				if (!sequence.next()) {
					throw new ExecutionException(this, "body not passed through");
				}
				xqExpression.bindItem(XQConstants.CONTEXT_ITEM, sequence.getItem());
			} else if (message.getBodyType() == BodyType.XQ_ITEM) {
				xqExpression.bindItem(XQConstants.CONTEXT_ITEM, message.<XQItem> getBody());
			} else if (message.getBodyType() == BodyType.INVALID) {
				// Nothing to bind
			} else {
				xqExpression.bindDocument(XQConstants.CONTEXT_ITEM, message.getBodyAsSource(), null);
			}
		}
		for (String bindName : _bindNames) {
			bind(bindName, new QName(bindName), xqExpression, context, message);
		}
		context.getTimeGauge().stopTimeMeasurement("bindDocument", true);
		XQResultSequence resultSequence = xqExpression.executeQuery();
		context.getTimeGauge().stopTimeMeasurement("executeQuery", true);
		try {
			processSequence(context, resultSequence, destMap);
		} finally {
			context.getTimeGauge().stopTimeMeasurement("processSequence", false);
		}
		if (_contextItem == null) {
			if (isPipelineStop()) {
				if (!resultSequence.next()) {
					throw new ExecutionException(this, "body not passed through");
				}
				message.reset(BodyType.XQ_ITEM, context.getXQDataFactory().createItem(resultSequence.getItem()));
			} else {
				message.reset(BodyType.XQ_SEQUENCE, resultSequence);
			}
		}
		return new ExecutionContext(resultSequence);
	}

	private void bind(String bindName, QName qName, XQPreparedExpression xqExpression, Context context, ESBMessage message) throws Exception {
		Object header = message.getHeader(bindName);
		Object variable = message.getVariable(bindName);
		if (header != null && variable == null) {
			xqExpression.bindObject(qName, header, null);
		} else if (header == null && variable != null) {
			if (variable instanceof Node) {
				xqExpression.bindNode(qName, (Node) variable, null);
			} else {
				xqExpression.bindObject(qName, variable, null);
			}
		} else if (header == null && variable == null) {
			xqExpression.bindString(qName, "", context.getXQDataFactory().createAtomicType(XQItemType.XQBASETYPE_STRING));
		} else {
			throw new ExecutionException(this, "name could not unambiguously be resolved: " + bindName);
		}
	}
	
	protected void processSequence(Context context, XQResultSequence resultSequence, Map<String, Object> destMap) throws Exception {
		if (destMap == null && !_varNames.isEmpty()) {
			throw new ExecutionException(this, "Cannot handle assignment");
		}
		for (String varName : _varNames) {
			if (!resultSequence.next()) {
				throw new ExecutionException(this, "Expression for " + varName + " had no result");
			}
			if (resultSequence.getItemType().getItemKind() == XQItemType.XQITEMKIND_TEXT) {
				destMap.put(varName, resultSequence.getItemAsString(null));
			} else {
				destMap.put(varName, resultSequence.getObject());
			}
		}
	}

	@Override
	protected final void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		if (nextActionIsPipelineStop && execContext != null) {
			XQResultSequence resultSequence = execContext.getResource();
			if (resultSequence.next()) {
				if (_contextItem == null) {
					context.getTimeGauge().startTimeMeasurement();
					if (message.isSink()) {
						resultSequence.writeItemToResult(message.getBodyAsSinkResult());
						context.getTimeGauge().stopTimeMeasurement("resultSequence.writeItemToResult", false);
					} else {
						message.reset(BodyType.XQ_ITEM, context.getXQDataFactory().createItem(resultSequence.getItem()));
						context.getTimeGauge().stopTimeMeasurement("getXQDataFactory().createItem", false);
					}
				}
			} else {
				throw new ExecutionException(this, "body not passed through");
			}
		}
	}

	@Override
	protected final void close(ExecutionContext execContext) throws Exception {
		if (execContext != null) {
			XQResultSequence resultSequence = execContext.getResource();
			if (resultSequence.next() && _contextItem == null) {
				logger.fine("XQResultSequence not fully consumed");
				if (logger.isLoggable(Level.FINE)) {
					resultSequence.writeItemToResult(new StreamResult(System.err));
				}
			}
			resultSequence.close();
		}
	}

//	@Override
//	public String toString() {
//		return getClass().getSimpleName() + " [_xquery=" + _xquery + ", _varNames=" + _varNames + ", _bindNames=" + _bindNames + ", _contextItem=" + _contextItem + "]";
//	}

}
