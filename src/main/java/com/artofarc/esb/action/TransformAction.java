/*
 * Copyright 2021 Andre Karalus
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import javax.xml.transform.sax.SAXSource;
import javax.xml.xquery.*;

import org.w3c.dom.Node;
import org.w3c.dom.Text;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.Attachments2SAX;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.DataStructures;
import com.artofarc.util.XQuerySource;

public class TransformAction extends Action {

	protected static final boolean BIND_NULL_CHECK = Boolean.parseBoolean(System.getProperty("esb0.bindNullCheck"));
	protected static final boolean ASSIGN_NULL_CHECK = Boolean.parseBoolean(System.getProperty("esb0.assignNullCheck"));

	public final static class Assignment {
		final String name, type;
		final boolean header, list;
		final Boolean nullable;
		String expr;

		public Assignment(String name, boolean header, String expr, Boolean nullable, String type) {
			this.name = name != null ? name.intern() : null;
			this.type = type != null ? type.intern() : null;
			this.header = header;
			this.nullable = nullable;
			list = type != null && (type.endsWith("+") || type.endsWith("*"));
			this.expr = expr;
		}

		public Assignment(String name, boolean nullable) {
			this(name, false, null, nullable ? Boolean.TRUE : null, null);
		}
	}

	private final XQuerySource _xquery;
	private final Set<String> _checkNotNull;
	private final List<Assignment> _assignments;
	private final boolean _clearSchema, _clearHeaders;
	private final String _baseURI;
	protected final String _contextItem;
	private final String _newContentType;

	public TransformAction(XQuerySource xquery, Set<String> checkNotNull, List<Assignment> assignments, boolean clearSchema, String baseURI, String contextItem, boolean clearHeaders, String newContentType) {
		_xquery = xquery;
		_checkNotNull = checkNotNull != null ? checkNotNull : Collections.emptySet();
		_assignments = assignments;
		_clearSchema = clearSchema;
		_clearHeaders = clearHeaders;
		_baseURI = baseURI;
		_contextItem = contextItem != null ? contextItem.intern() : null;
		_newContentType = newContentType;
		_streamingToSink = _contextItem == null;
	}

	public TransformAction(XQuerySource xquery, String baseURI, String contextItem, boolean clearHeaders, String newContentType) {
		this(xquery, null, contextItem != null ? Collections.singletonList(new Assignment(contextItem, false)) : Collections.emptyList(), true, baseURI, contextItem, false, newContentType);
	}

	protected TransformAction(String xquery, List<Assignment> varNames, String newContentType) {
		this(XQuerySource.create(xquery), null, varNames, false, null, null, false, newContentType);
	}

	protected TransformAction(String xquery) {
		this(XQuerySource.create(xquery), null, Collections.emptyList(), false, null, null, false, null);
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
		if (_clearHeaders) {
			message.clearHeaders();
		}
		context.getTimeGauge().startTimeMeasurement();
		XQPreparedExpression xqExpression = context.getXQPreparedExpression(_xquery, _baseURI);
		context.getTimeGauge().stopTimeMeasurement("prepareExpression", true);
		if (_contextItem != null) {
			bind(context, xqExpression, XQConstants.CONTEXT_ITEM, null, XQSequenceType.OCC_EXACTLY_ONE, _contextItem != "none" ? resolve(message, _contextItem, true) : "");
		} else {
			if (message.isEmpty()) {
				// Nothing to bind, but we need a context item
				xqExpression.bindString(XQConstants.CONTEXT_ITEM, "", null);
			} else {
				message.alignAxis(context);
				switch (message.getBodyType()) {
				case XQ_SEQUENCE:
					XQSequence sequence = message.getBody();
					checkNext(sequence, "body");
					xqExpression.bindItem(XQConstants.CONTEXT_ITEM, sequence.getItem());
					break;
				case XQ_ITEM:
					xqExpression.bindItem(XQConstants.CONTEXT_ITEM, message.getBody());
					break;
				case DOM:
					xqExpression.bindNode(XQConstants.CONTEXT_ITEM, message.getBody(), null);
					break;
				case EXCEPTION:
					xqExpression.bindDocument(XQConstants.CONTEXT_ITEM, DataStructures.asXMLString(message.getBody()), null, null);
					break;
				default:
					if (HttpConstants.isNotXML(message.getContentType())) {
						if (HttpConstants.isBinary(message.getContentType())) {
							xqExpression.bindObject(XQConstants.CONTEXT_ITEM, message.getBodyAsByteArray(context), null);
						} else {
							xqExpression.bindString(XQConstants.CONTEXT_ITEM, message.getBodyAsString(context), null);
						}
					} else {
						xqExpression.bindDocument(XQConstants.CONTEXT_ITEM, message.getBodyAsSource(context), null);
					}
					break;
				}
			}
		}
		XQuerySource.ExternalVariables externalVariables = _xquery.getExternalVariables(xqExpression);
		for (int i = 0; i < externalVariables.getLength(); ++i) {
			QName name = externalVariables.getName(i);
			Object value;
			if (name.getLocalPart().equals("attachmentsHull")) {
				value = new SAXSource(new Attachments2SAX(message, false), null);
			} else if (name.getLocalPart().equals("attachments")) {
				value = new SAXSource(new Attachments2SAX(message, true), null);
			} else {
				value = resolve(message, name.getLocalPart(), true);
				if (value == null && _checkNotNull.contains(name.getLocalPart())) {
					throw new ExecutionException(this, "Must not be null: " + name);
				}
			}
			bind(context, xqExpression, name, externalVariables.getType(i), externalVariables.getOccurrence(i), value);
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
			message.reset(BodyType.XQ_SEQUENCE, resultSequence);
			message.removeHeader(HttpConstants.HTTP_HEADER_CONTENT_LENGTH);
			if (_newContentType != null) {
				message.setContentType((String) eval(_newContentType, context, message));
			}
			if (_clearSchema) {
				message.setSchema(null);
			}
		}
		return new ExecutionContext(resultSequence, xqExpression);
	}

	private void bind(Context context, XQPreparedExpression xqExpression, QName qName, XQItemType type, int itemOccurrence, Object value) throws ExecutionException {
		try {
			if (value instanceof XQItem) {
				xqExpression.bindItem(qName, (XQItem) value);
			} else if (value instanceof Iterable) {
				xqExpression.bindSequence(qName, context.getXQDataFactory().createSequence(((Iterable<?>) value).iterator()));
			} else if (value instanceof Exception) {
				xqExpression.bindDocument(qName, DataStructures.asXMLString((Exception) value), null, type);
			} else if (type != null && type.getItemKind() == XQItemType.XQITEMKIND_DOCUMENT) {
				xqExpression.bindDocument(qName, (Source) value, type);
			} else if (type != null && type.getItemKind() == XQItemType.XQITEMKIND_ELEMENT) {
				xqExpression.bindNode(qName, (Node) value, type);
			} else if (value != null) {
				xqExpression.bindObject(qName, value, type);
			} else if (itemOccurrence == XQSequenceType.OCC_EXACTLY_ONE || itemOccurrence == XQSequenceType.OCC_ONE_OR_MORE) {
				if (BIND_NULL_CHECK) {
					throw new ExecutionException(this, "Value is null but XQuery requires it for " + qName);
				}
				logger.info("Value is null but XQuery requires it for " + qName);
				xqExpression.bindString(qName, "", null);
			} else {
				// XQuery has no NULL value but the empty sequence
				xqExpression.bindSequence(qName, context.getXQEmptySequence());
			}
		} catch (XQException e) {
			throw new ExecutionException(this, "binding " + qName + (type != null ? " to " + type : "") + " failed", e);
		}
	}

	protected void processSequence(ESBMessage message, XQResultSequence resultSequence) throws ExecutionException {
		for (Assignment assignment : _assignments) {
			try {
				int count = 1;
				if (ASSIGN_NULL_CHECK || assignment.nullable != null || assignment.list) {
					checkNext(resultSequence, assignment.name);
					count = resultSequence.getInt();
				}
				switch (count) {
				case 0:
					if (Boolean.TRUE != assignment.nullable) {
						if (assignment.list) {
							message.getVariables().put(assignment.name, Collections.EMPTY_LIST);
						} else {
							throw new ExecutionException(this, "Must not be null: " + assignment.name);
						}
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
						logger.warn("Result is a list for " + assignment.name + " at " + toString());
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
		final Object object = resultSequence.getObject();
		return object instanceof Text ? ((Text) object).getData() : object;
	}

	@Override
	protected final void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		if (nextActionIsPipelineStop) {
			if (message.getBodyType() == BodyType.XQ_SEQUENCE) {
				XQSequence sequence = message.getBody();
				checkNext(sequence, "body");
				message.reset(BodyType.XQ_ITEM, context.getXQDataFactory().createItem(sequence.getItem()));
			} else if (_contextItem == null) {
				XQResultSequence resultSequence = execContext.getResource();
				if (resultSequence.next()) {
					if (message.isSink()) {
						context.getTimeGauge().startTimeMeasurement();
						message.writeItemToSink(resultSequence.getItem(), context);
						context.getTimeGauge().stopTimeMeasurement("XQItem::writeItem", false);
					} else {
						message.reset(BodyType.XQ_ITEM, context.getXQDataFactory().createItem(resultSequence.getItem()));
					}
				}
			}
		}
	}

	@Override
	protected final void close(Context context, ExecutionContext execContext, ESBMessage message, boolean exception) throws Exception {
		XQResultSequence resultSequence = execContext.getResource();
		if (message.getBody() == resultSequence) {
			checkNext(resultSequence, "body");
			message.reset(BodyType.XQ_ITEM, context.getXQDataFactory().createItem(resultSequence.getItem()));
		}
		if (!exception && resultSequence.next()) {
			logger.warn("XQResultSequence not fully consumed");
			if (logger.isDebugEnabled()) {
				context.writeItem(resultSequence, System.err, null);
			}
		}
		resultSequence.close();
		XQPreparedExpression xqExpression = execContext.getResource2();
		// unbind (large) documents so that they can be garbage collected
		xqExpression.bindString(XQConstants.CONTEXT_ITEM, "", null);
		XQuerySource.ExternalVariables externalVariables = _xquery.getExternalVariables(xqExpression);
		for (int i = 0; i < externalVariables.getLength(); ++i) {
			XQItemType externalVariableType = externalVariables.getType(i);
			if (externalVariableType != null && externalVariableType.getItemKind() != XQItemType.XQITEMKIND_ATOMIC || externalVariables.getOccurrence(i) == XQSequenceType.OCC_ONE_OR_MORE) {
				xqExpression.bindSequence(externalVariables.getName(i), context.getXQEmptySequence());
			}
		}
	}

}
