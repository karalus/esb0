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
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

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
import com.artofarc.util.Collections;

public class TransformAction extends Action {

	private final XQuerySource _xquery;
	private final Collection<Map.Entry<String, Boolean>> _varNames;
	private final String _baseURI; 
	private final String _contextItem;
	protected Collection<Map.Entry<String, Boolean>> _bindNames;
	private final HashMap<QName, Map.Entry<XQItemType, Boolean>> _bindings = new HashMap<>();

	public static Collection<Map.Entry<String, Boolean>> emptyNames() {
		return java.util.Collections.emptyList();
	}

	public TransformAction(XQuerySource xquery, Collection<Map.Entry<String, Boolean>> varNames, String baseURI, String contextItem) {
		_xquery = xquery;
		_varNames = varNames;
		_baseURI = baseURI;
		_contextItem = contextItem;
		_pipelineStop = contextItem != null;
	}

	public TransformAction(XQuerySource xquery, String baseURI, String contextItem) {
		this(xquery, contextItem != null ? Arrays.asList(Collections.createEntry(contextItem, false)) : emptyNames(), baseURI, contextItem);
	}

	protected TransformAction(String xquery, Collection<Map.Entry<String, Boolean>> varNames) {
		this(XQuerySource.create(xquery), varNames, null, null);
	}

	protected TransformAction(String xquery) {
		this(XQuerySource.create(xquery), emptyNames(), null, null);
	}

	public final XQuerySource getXQuery() {
		return _xquery;
	}

	private synchronized void initBindings(XQPreparedExpression xqExpression, XQDataFactory xqDataFactory, ESBMessage message) throws Exception {
		if (_bindNames == null) {
			_bindNames = new ArrayList<>();
			for (QName qName : xqExpression.getAllExternalVariables()) {
				boolean nullable = true;
				_bindNames.add(Collections.createEntry(qName.getLocalPart(), nullable));
				XQSequenceType sequenceType = xqExpression.getStaticVariableType(qName);
				_bindings.put(qName, Collections.createEntry(sequenceType.getItemType(), nullable));
			}
		} else {
			for (Map.Entry<String, Boolean> bindName : _bindNames) {
				Object value = resolve(message, bindName.getKey(), true);
				XQItemType itemType = value != null ? xqDataFactory.createItemFromObject(value, null).getItemType() : xqDataFactory.createItemType();
				_bindings.put(new QName(bindName.getKey()), Collections.createEntry(itemType, bindName.getValue()));
			}
		}
	}

	protected final void checkNext(XQSequence sequence, String goal) throws XQException, ExecutionException {
		if (!sequence.next()) {
			throw new ExecutionException(this, "Sequence prematurely ended. Could not get " + goal);
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
			bind(_contextItem, XQConstants.CONTEXT_ITEM, null, true, xqExpression, message);
		} else {
			if (message.isEmpty()) {
				// Nothing to bind, but we need a context item
				xqExpression.bindString(XQConstants.CONTEXT_ITEM, "", null);
			} else {
				switch (message.getBodyType()) {
				case XQ_SEQUENCE:
					XQSequence sequence = message.getBody();
					checkNext(sequence, "body");
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
		for (Map.Entry<QName, Map.Entry<XQItemType, Boolean>> entry : _bindings.entrySet()) {
			bind(entry.getKey().getLocalPart(), entry.getKey(), entry.getValue().getKey(), entry.getValue().getValue(), xqExpression, message);
		}
		context.getTimeGauge().stopTimeMeasurement("bindDocument", true);
		XQResultSequence resultSequence = xqExpression.executeQuery();
		context.getTimeGauge().stopTimeMeasurement("executeQuery", true);
		try {
			processSequence(message, resultSequence, destMap);
		} finally {
			context.getTimeGauge().stopTimeMeasurement("processSequence", false);
		}
		if (_contextItem == null) {
			if (isPipelineStop()) {
				checkNext(resultSequence, "body");
				message.reset(BodyType.XQ_ITEM, context.getXQDataFactory().createItem(resultSequence.getItem()));
			} else {
				message.reset(BodyType.XQ_SEQUENCE, resultSequence);
			}
		}
		return new ExecutionContext(resultSequence, xqExpression);
	}

	private void bind(String bindName, QName qName, XQItemType type, boolean nullable, XQPreparedExpression xqExpression, ESBMessage message) throws Exception {
		Object value = resolve(message, bindName, true);
		if (value != null) {
			try {
				xqExpression.bindObject(qName, value, type);
			} catch (XQException e) {
				throw new ExecutionException(this, "binding " + bindName + " failed", e);
			}
		} else if (nullable) {
			// Workaround: XQuery has no NULL value
			xqExpression.bindString(qName, "", null);
		} else {
			throw new ExecutionException(this, "Must not be null: " + bindName);
		}
	}

	protected void processSequence(ESBMessage message, XQResultSequence resultSequence, Map<String, Object> destMap) throws Exception {
		for (Map.Entry<String, Boolean> entry : _varNames) {
			String varName  = entry.getKey();
			boolean notNull = true;
			if (entry.getValue()) {
				checkNext(resultSequence, varName);
				notNull = resultSequence.getBoolean();
			}
			if (notNull) {
				checkNext(resultSequence, varName);
				if (resultSequence.getItemType().getItemKind() == XQItemType.XQITEMKIND_TEXT) {
					destMap.put(varName, resultSequence.getItemAsString(null));
				} else {
					destMap.put(varName, resultSequence.getObject());
				}
			}
		}
	}

	@Override
	protected final void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		if (nextActionIsPipelineStop && execContext != null) {
			XQResultSequence resultSequence = execContext.getResource();
			checkNext(resultSequence, "body");
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
			for (Map.Entry<QName, Map.Entry<XQItemType, Boolean>> entry : _bindings.entrySet()) {
				if (entry.getValue() == null || entry.getValue().getKey().getItemKind() != XQItemType.XQITEMKIND_ATOMIC) {
					xqExpression.bindString(entry.getKey(), "", null);
				}
			}
		}
	}

}
