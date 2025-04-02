/*
 * Copyright 2022 Andre Karalus
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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map.Entry;
import java.util.Set;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.DataStructures;
import com.artofarc.util.ReflectionUtils;
import com.artofarc.util.StringWrapper;

public class SetMessageAction extends ForwardAction {

	private final ClassLoader _classLoader;
	private final ArrayList<Assignment> _assignments = new ArrayList<>();
	private final Assignment _body;
	private Set<String> _clearHeadersExcept;

	public SetMessageAction(ClassLoader cl, String javaType, String method, StringWrapper bodyExpr, boolean fromFile) throws ReflectiveOperationException {
		_classLoader = cl;
		_body = bodyExpr != null ? new Assignment("body", false, javaType, method, null, bodyExpr, fromFile) : null;
		_pipelineStop = bodyExpr != null;
	}

	public final void addAssignment(String name, boolean header, String javaType, String method, String field, StringWrapper expr, boolean fromFile) throws ReflectiveOperationException {
		Assignment assignment = new Assignment(name, header, javaType, method, field, expr, fromFile);
		_assignments.add(assignment);
		if (assignment._needsBody) {
			_pipelineStop = true;
		}
	}

	public final void addAssignment(String name, boolean header, String expr, String javaType, String method, String field) throws ReflectiveOperationException {
		addAssignment(name, header, javaType, method, field, StringWrapper.create(expr), false);
	}

	public final void setClearHeadersExcept(Set<String> clearHeadersExcept) {
		_clearHeadersExcept = clearHeadersExcept;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		if (_clearHeadersExcept != null) {
			message.clearHeadersExcept(_clearHeadersExcept);
		}
		for (Assignment assignment : _assignments) {
			if (assignment._needsBody) {
				break;
			} else {
				assignment.assign(context, message);
			}
		}
		return super.prepare(context, message, inPipeline);
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		boolean forBody = false;
		for (Assignment assignment : _assignments) {
			if (forBody |= assignment._needsBody) {
				assignment.assign(context, message);
			}
		}
		if (_body != null) {
			message.reset(null, _body.evaluate(context, message));
			message.evaluateContentType();
			message.setSchema(null);
			if (_clearHeadersExcept == null) {
				message.removeHeader(HttpConstants.HTTP_HEADER_CONTENT_LENGTH);
			}
		}
		super.execute(context, execContext, message, nextActionIsPipelineStop);
	}

	final class Assignment {

		final String _name;
		final boolean _header;
		final StringWrapper _expr;
		final boolean _fromFile;
		final boolean _needsBody;
		final Entry<Class<?>, MethodHandle>[] _methodHandles;

		Assignment(String name, boolean header, String javaType, String method, String field, StringWrapper expr, boolean fromFile) throws ReflectiveOperationException {
			_name = name.intern();
			_header = header;
			_expr = expr;
			_fromFile = fromFile;
			_needsBody = fromFile ? false : expr.getString().contains("${body") || expr.getString().contains("${rawBody");
			if (javaType != null) {
				try {
					Class<?> cls = Class.forName(javaType, true, _classLoader);
					if (method != null) {
						_methodHandles = ReflectionUtils.findStaticMethods(cls, method, expr.isEmpty() ? 0 : 1);
					} else if (field != null) {
						if (!expr.isEmpty()) {
							throw new IllegalArgumentException("Field must not have an expression");
						}
						Field _field = cls.getField(field);
						ReflectionUtils.checkStatic(_field);
						_methodHandles = DataStructures.toSingletonArray(DataStructures.createEntry(null, MethodHandles.publicLookup().unreflectGetter(_field)));
					} else {
						_methodHandles = ReflectionUtils.findConstructors(cls, expr.isEmpty() ? 0 : 1);
					}
				} catch (LinkageError e) {
					throw new IllegalStateException("Could not load Java class " + javaType, e);
				}
			} else {
				_methodHandles = null;
			}
		}

		Object evaluate(Context context, ESBMessage message) throws Exception {
			Object value = _fromFile ? _expr.getString() : eval(_expr.getString(), context, message);
			if (_methodHandles != null) {
				try {
					if (_expr.isEmpty()) {
						return _methodHandles[0].getValue().invoke();
					} else if (_methodHandles.length == 1) {
						return _methodHandles[0].getValue().invoke(value);
					} else {
						return ReflectionUtils.invokePolymorphic(_methodHandles, value);
					}
				} catch (Throwable e) {
					throw ReflectionUtils.convert(e, Exception.class);
				}
			}
			return value;
		}

		void assign(Context context, ESBMessage message) throws Exception {
			if (_header) {
				message.putHeader(_name, evaluate(context, message));
			} else {
				message.putVariable(_name, evaluate(context, message));
			}
		}
	}

}
