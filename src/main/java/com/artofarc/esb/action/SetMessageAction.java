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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.ReflectionUtils;
import com.artofarc.util.StringWrapper;

public class SetMessageAction extends ForwardAction {

	private final boolean _clearAll;
	private final ClassLoader _classLoader;
	private final ArrayList<Assignment> _assignments = new ArrayList<>();
	private final Assignment _body; 

	public SetMessageAction(boolean clearAll, ClassLoader cl, StringWrapper bodyExpr, String javaType, String method) throws ReflectiveOperationException {
		_clearAll = clearAll;
		_classLoader = cl;
		_body = bodyExpr != null ? new Assignment(null, false, bodyExpr, javaType, method, null) : null;
		_pipelineStop = bodyExpr != null;
		_pipelineStart = false;
	}

	public final void addAssignment(String name, boolean header, String expr, String javaType, String method, String field) throws ReflectiveOperationException {
		Assignment assignment = new Assignment(name, header, new StringWrapper(expr), javaType, method, field);
		_assignments.add(assignment);
		if (assignment._needsBody) {
			_pipelineStop = true;
		}
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		if (_clearAll) {
			message.clearHeaders();
		}
		for (Assignment assignment : _assignments) {
			if (assignment._needsBody) {
				break;
			} else {
				assignment.assign(message, bindVariable(assignment._expr.getString(), context, message));
			}
		}
		return super.prepare(context, message, inPipeline);
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		boolean forBody = false;
		for (Assignment assignment : _assignments) {
			if (forBody |= assignment._needsBody) {
				assignment.assign(message, bindVariable(assignment._expr.getString(), context, message));
			}
		}
		if (_body != null) {
			message.reset(null, _body.convert(bindVariable(_body._expr.getString(), context, message)));
			if (!_clearAll) {
				message.removeHeader(HttpConstants.HTTP_HEADER_CONTENT_LENGTH);
			}
		}
		super.execute(context, execContext, message, nextActionIsPipelineStop);
	}

	final class Assignment {

		final String _name;
		final boolean _header;
		final StringWrapper _expr;
		final boolean _needsBody;
		final MethodHandle _methodHandle;

		Assignment(String name, boolean header, StringWrapper expr, String javaType, String method, String field) throws ReflectiveOperationException {
			_name = name;
			_header = header;
			_expr = expr;
			_needsBody = expr.getString().contains("${body");
			if (javaType != null) {
				Class<?> cls = Class.forName(javaType, true, _classLoader);
				if (method != null) {
					Method _method = _expr.isEmpty() ? cls.getMethod(method) : ReflectionUtils.findAnyMethod(cls, method, String.class, Long.TYPE, Long.class, Integer.TYPE, Integer.class);
					ReflectionUtils.checkStatic(_method);
					_methodHandle = MethodHandles.publicLookup().unreflect(_method);
				} else if (field != null) {
					if (!expr.isEmpty()) {
						throw new IllegalArgumentException("Field must not have an expression");
					}
					Field _field = cls.getField(field);
					ReflectionUtils.checkStatic(_field);
					_methodHandle = MethodHandles.publicLookup().unreflectGetter(_field);
				} else {
					Constructor<?> con = _expr.isEmpty() ? cls.getConstructor() : ReflectionUtils.findAnyConstructor(cls, String.class, Long.TYPE, Long.class, Integer.TYPE, Integer.class);
					_methodHandle = MethodHandles.publicLookup().unreflectConstructor(con);
				}
			} else {
				_methodHandle = null;
			}
		}

		Object convert(Object value) throws Exception {
			if (_methodHandle != null) {
				try {
					return _expr.isEmpty() ? _methodHandle.invoke() : _methodHandle.invoke(value);
				} catch (Throwable e) {
					throw ReflectionUtils.convert(e, Exception.class);
				}
			}
			return value;
		}

		void assign(ESBMessage message, Object value) throws Exception {
			if (_header) {
				message.putHeader(_name, convert(value));
			} else {
				message.putVariable(_name, convert(value));
			}
		}
	}

}
