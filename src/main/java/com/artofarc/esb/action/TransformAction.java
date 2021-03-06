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
import java.util.Set;

import javax.xml.namespace.QName;
import javax.xml.transform.Source;
import javax.xml.xquery.*;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.XQuerySource;

public class TransformAction extends Action {

	public final static class Assignment {
		final String name, type;
		final boolean header, nullable, list;
		String expr;

		public Assignment(String name, boolean header, String expr, boolean nullable, String type) {
			this.name = name != null ? name.intern() : null;
			this.type = type != null ? type.intern() : null;
			this.header = header;
			this.nullable = nullable;
			list = type != null && (type.endsWith("+") || type.endsWith("*"));
			if (list && header) {
				throw new IllegalArgumentException("header must not be a list");
			}
			this.expr = expr;
		}

		public Assignment(String name, boolean nullable) {
			this(name, false, null, nullable, null);
		}
	}

	private final XQuerySource _xquery;
	private final Set<String> _checkNotNull;
	private final List<Assignment> _assignments;
	private final String _baseURI; 
	protected final String _contextItem;

	public TransformAction(XQuerySource xquery, Set<String> checkNotNull, List<Assignment> assignments, String baseURI, String contextItem) {
		_xquery = xquery;
		_checkNotNull = checkNotNull != null ? checkNotNull : Collections.emptySet();
		_assignments = assignments;
		_baseURI = baseURI;
		_contextItem = contextItem;
		_pipelineStop = contextItem != null;
	}

	public TransformAction(XQuerySource xquery, String baseURI, String contextItem) {
		this(xquery, null, contextItem != null ? Collections.singletonList(new Assignment(contextItem, false)) : Collections.emptyList(), baseURI, contextItem);
	}

	protected TransformAction(String xquery, List<Assignment> varNames) {
		this(XQuerySource.create(xquery), null, varNames, null, null);
	}

	protected TransformAction(String xquery) {
		this(XQuerySource.create(xquery), null, Collections.emptyList(), null, null);
	}

	public final XQuerySource getXQuery() {
		return _xquery;
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
		context.getTimeGauge().stopTimeMeasurement("prepareExpression", true);
		if (_contextItem != null) {
			bind(context, xqExpression, XQConstants.CONTEXT_ITEM, null, resolve(message, _contextItem, true));
		} else {
			if (message.isEmpty()) {
				// Nothing to bind, but we need a context item
				xqExpression.bindString(XQConstants.CONTEXT_ITEM, "", null);
			} else {
				String contentType = message.getHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE);
				if (HttpConstants.isNotSOAP(contentType)) {
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
		QName[] externalVariables = _xquery.getExternalVariables();
		XQItemType[] externalVariableTypes = _xquery.getExternalVariableTypes();
		for (int i = 0; i < externalVariables.length; ++i) {
			QName name = externalVariables[i];
			Object value = resolve(message, name.getLocalPart(), true);
			if (externalVariableTypes[i] == null && value != null) {
				externalVariableTypes[i] = context.getXQDataFactory().createItemFromObject(value, null).getItemType();
				// Memory barrier to make this visible to other threads
				_xquery.setExternalVariableTypes(externalVariableTypes);
			}
			if (value == null && _checkNotNull.contains(name.getLocalPart())) {
				throw new ExecutionException(this, "Must not be null: " + name);
			}
			bind(context, xqExpression, name, externalVariableTypes[i], value);
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

	private void bind(Context context, XQPreparedExpression xqExpression, QName qName, XQItemType type, Object value) throws ExecutionException {
		try {
			if (value != null) {
				if (type != null && type.getItemKind() == XQItemType.XQITEMKIND_DOCUMENT) {
					xqExpression.bindDocument(qName, (Source) value, type);
				} else if (value instanceof Iterable) {
					XQSequence xqSequence = context.getXQDataFactory().createSequence(((Iterable<?>) value).iterator());
					xqExpression.bindSequence(qName, xqSequence);
				} else {
					xqExpression.bindObject(qName, value, type);
				}
			} else {
				// Workaround: XQuery has no NULL value
				xqExpression.bindString(qName, "", null);
			}
		} catch (XQException e) {
			throw new ExecutionException(this, "binding " + qName + " failed", e);
		}
	}

	protected void processSequence(ESBMessage message, XQResultSequence resultSequence) throws ExecutionException {
		for (Assignment assignment : _assignments) {
			try {
				int count = 1;
				if (assignment.nullable || assignment.list) {
					checkNext(resultSequence, assignment.name);
					count = resultSequence.getInt();
				}
				switch (count) {
				case 0:
					if (assignment.list && !assignment.nullable) {
						message.getVariables().put(assignment.name, Collections.emptyList());
					}
					break;
				case 1:
					checkNext(resultSequence, assignment.name);
					Object value = next(resultSequence);
					if (assignment.header) {
						message.putHeader(assignment.name, value);
					} else {
						message.getVariables().put(assignment.name, assignment.list ? Collections.singletonList(value) : value);
					}
					break;
				default:
					List<Object> result = new ArrayList<>(count);
					while (count-- > 0) {
						checkNext(resultSequence, assignment.name);
						result.add(next(resultSequence));
					}
					if (assignment.list) {
						message.getVariables().put(assignment.name, result);
					} else {
						logger.warn("Result is a list in " + toString());
						message.getVariables().put(assignment.name, result.get(0));
					}
					break;
				}
			} catch (XQException e) {
				throw new ExecutionException(this, "processing " + assignment.name + " failed", e);
			}
		}
	}

	private static Object next(XQResultSequence resultSequence) throws XQException {
		if (resultSequence.getItemType().getItemKind() == XQItemType.XQITEMKIND_TEXT) {
			return resultSequence.getItemAsString(null);
		} else {
			return resultSequence.getObject();
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
	protected final void close(ExecutionContext execContext, ESBMessage message, boolean exception) throws Exception {
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
			QName[] externalVariables = _xquery.getExternalVariables();
			XQItemType[] externalVariableTypes = _xquery.getExternalVariableTypes();
			for (int i = 0; i < externalVariables.length; ++i) {
				XQItemType externalVariableType = externalVariableTypes[i];
				if (externalVariableType != null && externalVariableType.getItemKind() != XQItemType.XQITEMKIND_ATOMIC) {
					xqExpression.bindString(externalVariables[i], "", null);
				}
			}
		}
	}

}
