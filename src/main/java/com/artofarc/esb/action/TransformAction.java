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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.xml.namespace.QName;
import javax.xml.xquery.XQConstants;
import javax.xml.xquery.XQDataFactory;
import javax.xml.xquery.XQException;
import javax.xml.xquery.XQItem;
import javax.xml.xquery.XQItemType;
import javax.xml.xquery.XQPreparedExpression;
import javax.xml.xquery.XQResultSequence;
import javax.xml.xquery.XQSequence;
import javax.xml.xquery.XQSequenceType;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.XQuerySource;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;

public class TransformAction extends Action {

	private final XQuerySource _xquery;
	private final List<String> _varNames;
	private final String _baseURI; 
	protected List<String> _bindNames;
	private final HashMap<QName, XQItemType> _bindings = new HashMap<>();
	protected String _contextItem;

	protected TransformAction(XQuerySource xquery, List<String> varNames, String baseURI) {
		_xquery = xquery;
		_varNames = varNames;
		_baseURI = baseURI;
	}

	public TransformAction(XQuerySource xquery, String baseURI) {
		this(xquery, Collections.<String> emptyList(), baseURI);
	}

	protected TransformAction(String xquery, List<String> varNames) {
		this(new XQuerySource(xquery), varNames, null);
	}

	protected TransformAction(String xquery) {
		this(new XQuerySource(xquery), null);
	}

	private synchronized void initBindings(XQPreparedExpression xqExpression, XQDataFactory xqDataFactory, ESBMessage message) throws Exception {
		if (_bindNames == null) {
			_bindNames = new ArrayList<>();
			for (QName qName : xqExpression.getAllExternalVariables()) {
				_bindNames.add(qName.getLocalPart());
				XQSequenceType sequenceType = xqExpression.getStaticVariableType(qName);
				_bindings.put(qName, sequenceType.getItemType());
			}
		} else {
			for (String bindName : _bindNames) {
				Object value = resolve(message, bindName, true);
				XQItemType itemType = value != null ? xqDataFactory.createItemFromObject(value, null).getItemType() : null;
				_bindings.put(new QName(bindName), itemType);
			}
		}
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		return prepare(context, message, message.getVariables());
	}

	protected final ExecutionContext prepare(Context context, ESBMessage message, Map<String, Object> destMap) throws Exception {
		context.getTimeGauge().startTimeMeasurement();
		XQPreparedExpression xqExpression = context.getXQPreparedExpression(_xquery, _baseURI);
		if (_bindNames == null || _bindNames.size() != _bindings.size()) {
			initBindings(xqExpression, context.getXQDataFactory(), message);
		}
		context.getTimeGauge().stopTimeMeasurement("prepareExpression", true);
		if (_contextItem != null) {
			bind(_contextItem, XQConstants.CONTEXT_ITEM, null, xqExpression, message);
		} else {
			if (message.isEmpty()) {
				// Nothing to bind, but we need a context item
				xqExpression.bindString(XQConstants.CONTEXT_ITEM, "", null);
			} else {
				switch (message.getBodyType()) {
				case XQ_SEQUENCE:
					XQSequence sequence = message.getBody();
					if (!sequence.next()) {
						throw new ExecutionException(this, "body not passed through");
					}
					xqExpression.bindItem(XQConstants.CONTEXT_ITEM, sequence.getItem());
					break;
				case XQ_ITEM:
					xqExpression.bindItem(XQConstants.CONTEXT_ITEM, message.<XQItem> getBody());
					break;
				default:
					xqExpression.bindDocument(XQConstants.CONTEXT_ITEM, message.getBodyAsSource(context), null);
					break;
				}
			}
		}
		for (Entry<QName, XQItemType> entry : _bindings.entrySet()) {
			bind(entry.getKey().getLocalPart(), entry.getKey(), entry.getValue(), xqExpression, message);
		}
		context.getTimeGauge().stopTimeMeasurement("bindDocument", true);
		XQResultSequence resultSequence = xqExpression.executeQuery();
		context.getTimeGauge().stopTimeMeasurement("executeQuery", true);
		try {
			processSequence(context, message, resultSequence, destMap);
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
		return new ExecutionContext(resultSequence, xqExpression);
	}

	private void bind(String bindName, QName qName, XQItemType type, XQPreparedExpression xqExpression, ESBMessage message) throws Exception {
		Object value = resolve(message, bindName, true);
		if (value != null) {
			try {
				xqExpression.bindObject(qName, value, type);
			} catch (XQException e) {
				throw new ExecutionException(this, "binding " + bindName + " failed", e);
			}
		} else {
			// Workaround: XQuery has no NULL value
			xqExpression.bindString(qName, "", null);
		}
	}

	protected void processSequence(Context context, ESBMessage message, XQResultSequence resultSequence, Map<String, Object> destMap) throws Exception {
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
						resultSequence.writeItemToResult(message.getBodyAsSinkResult(context));
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
				logger.debug("XQResultSequence not fully consumed");
				if (logger.isDebugEnabled()) {
					resultSequence.writeItem(System.err, null);
				}
			}
			resultSequence.close();
			XQPreparedExpression xqExpression = execContext.getResource2();
			// unbind (large) documents so that they can be garbage collected
			xqExpression.bindString(XQConstants.CONTEXT_ITEM, "", null);
			for (Entry<QName, XQItemType> entry : _bindings.entrySet()) {
				if (entry.getValue() == null || entry.getValue().getItemKind() != XQItemType.XQITEMKIND_ATOMIC) {
					xqExpression.bindString(entry.getKey(), "", null);
				}
			}
		}
	}

}
