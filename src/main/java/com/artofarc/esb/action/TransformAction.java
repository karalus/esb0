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

import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.service.XQDecl;
import com.artofarc.util.Collections;

public class TransformAction extends Action {

	public final static class Assignment {
		final String name;
		final boolean header;
		String expr;
		final boolean nullable;
		String type;

		public Assignment(String name, boolean header, String expr, boolean nullable, String type) {
			this.name = name;
			this.header = header;
			this.expr = expr;
			this.nullable = nullable;
			this.type = type;
		}
	}

	private static List<Assignment> emptyNames() {
		return java.util.Collections.emptyList();
	}

	private final XQuerySource _xquery;
	private final List<Assignment> _assignments;
	private final String _baseURI; 
	private final String _contextItem;
	protected List<XQDecl> _bindNames;
	private final Map<QName, Map.Entry<XQItemType, Boolean>> _bindings = new HashMap<>();

	public TransformAction(XQuerySource xquery, List<Assignment> assignments, String baseURI, String contextItem) {
		_xquery = xquery;
		_assignments = assignments;
		_baseURI = baseURI;
		_contextItem = contextItem;
		_pipelineStop = contextItem != null;
	}

	public TransformAction(XQuerySource xquery, String baseURI, String contextItem) {
		this(xquery, contextItem != null ? Arrays.asList(new Assignment(contextItem, false, null, false, null)) : emptyNames(), baseURI, contextItem);
	}

	protected TransformAction(String xquery, List<Assignment> varNames) {
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
				Boolean nullable = true;
				XQDecl xqDecl = new XQDecl();
				xqDecl.setValue(qName.getLocalPart());
				xqDecl.setNullable(nullable);
				_bindNames.add(xqDecl);
				XQSequenceType sequenceType = xqExpression.getStaticVariableType(qName);
				_bindings.put(qName, Collections.createEntry(sequenceType.getItemType(), nullable));
			}
		} else {
			for (XQDecl bindName : _bindNames) {
				QName qName = new QName(bindName.getValue());
				if (bindName.getType() != null) {
					XQSequenceType sequenceType = xqExpression.getStaticVariableType(qName);
					_bindings.put(qName, Collections.createEntry(sequenceType.getItemType(), bindName.isNullable()));
				} else {
					// type cannot be determined statically, take sample from message
					Object value = resolve(message, bindName.getValue(), true);
					XQItemType itemType = value != null ? xqDataFactory.createItemFromObject(value, null).getItemType() : xqDataFactory.createItemType();
					_bindings.put(qName, Collections.createEntry(itemType, bindName.isNullable()));
				}
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
				String contentType = message.getHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE);
				if (contentType != null && contentType.startsWith(HttpConstants.HTTP_HEADER_CONTENT_TYPE_JSON)) {
					xqExpression.bindString(XQConstants.CONTEXT_ITEM, message.getBodyAsString(context), null);
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
		}
		for (Map.Entry<QName, Map.Entry<XQItemType, Boolean>> entry : _bindings.entrySet()) {
			QName name = entry.getKey();
			Map.Entry<XQItemType, Boolean> typeDecl = entry.getValue();
			bind(name.getLocalPart(), name, typeDecl.getKey(), typeDecl.getValue(), xqExpression, message);
		}
		context.getTimeGauge().stopTimeMeasurement("bindDocument", true);
		XQResultSequence resultSequence = xqExpression.executeQuery();
		context.getTimeGauge().stopTimeMeasurement("executeQuery", true);
		try {
			processSequence(message, resultSequence);
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
				if (type != null && type.getItemKind() == XQItemType.XQITEMKIND_DOCUMENT) {
					xqExpression.bindDocument(qName, (Reader) value, null, type);
				} else {
					xqExpression.bindObject(qName, value, type);
				}
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

	protected void processSequence(ESBMessage message, XQResultSequence resultSequence) throws Exception {
		for (Assignment assignment : _assignments) {
			boolean notNull = true;
			if (assignment.nullable) {
				checkNext(resultSequence, assignment.name);
				notNull = resultSequence.getBoolean();
			}
			if (notNull) {
				checkNext(resultSequence, assignment.name);
				final Object value;
				if (resultSequence.getItemType().getItemKind() == XQItemType.XQITEMKIND_TEXT) {
					value = resultSequence.getItemAsString(null);
				} else {
					value = resultSequence.getObject();
				}
				if (assignment.header) {
					message.putHeader(assignment.name, value);
				} else {
					message.getVariables().put(assignment.name, value);
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
				Map.Entry<XQItemType, Boolean> typeDecl = entry.getValue();
				if (typeDecl == null || typeDecl.getKey().getItemKind() != XQItemType.XQITEMKIND_ATOMIC) {
					xqExpression.bindString(entry.getKey(), "", null);
				}
			}
		}
	}

}
