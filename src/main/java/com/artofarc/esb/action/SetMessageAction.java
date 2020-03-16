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

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.ReflectionUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

public class SetMessageAction extends ForwardAction {

	private final boolean _clearAll;
	private final ClassLoader _classLoader;
	private final List<Assignment> _assignments = new ArrayList<>();
	private final Assignment _body; 

	public SetMessageAction(boolean clearAll, ClassLoader cl, String bodyExpr, String javaType, String method) throws ClassNotFoundException, NoSuchMethodException {
		_clearAll = clearAll;
		_classLoader = cl;
		_body = bodyExpr != null ? new Assignment(null, false, bodyExpr, javaType, method) : null;
		_pipelineStop = bodyExpr != null;
		_pipelineStart = false;
	}

	public final void addAssignment(String name, boolean header, String expr, String javaType, String method) throws ClassNotFoundException, NoSuchMethodException {
		Assignment assignment = new Assignment(name, header, expr, javaType, method);
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
				assignment.assign(message, bindVariable(assignment._expr, context, message));
			}
		}
		return super.prepare(context, message, inPipeline);
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		boolean forBody = false;
		for (Assignment assignment : _assignments) {
			if (forBody |= assignment._needsBody) {
				assignment.assign(message, bindVariable(assignment._expr, context, message));
			}
		}
		if (_body != null) {
			message.reset(null, _body.convert(bindVariable(_body._expr, context, message)));
			if (!_clearAll) {
				message.removeHeader(HttpConstants.HTTP_HEADER_CONTENT_LENGTH);
			}
		}
		super.execute(context, execContext, message, nextActionIsPipelineStop);
	}

	final class Assignment {

		final String _name;
		final boolean _header;
		final String _expr;
		final boolean _needsBody;
		Constructor<?> _con;
		Method _method;

		Assignment(String name, boolean header, String expr, String javaType, String method) throws ClassNotFoundException, NoSuchMethodException {
			_name = name;
			_header = header;
			_expr = expr;
			_needsBody = expr.contains("${body");
			if (javaType != null) {
				Class<?> cls = Class.forName(javaType, true, _classLoader);
				if (method != null) {
					_method = _expr.isEmpty() ? cls.getMethod(method) : ReflectionUtils.findAnyMethod(cls, method, String.class, Long.TYPE, Long.class, Integer.TYPE, Integer.class);
					if ((_method.getModifiers() & Modifier.STATIC) == 0) {
						throw new IllegalArgumentException("Method must be static: " + method);
					}
				} else {
					_con = _expr.isEmpty() ? cls.getConstructor() : ReflectionUtils.findAnyConstructor(cls, String.class, Long.TYPE, Long.class, Integer.TYPE, Integer.class);
				}
			}
		}

		Object convert(Object value) throws Exception {
			try {
				if (_con != null) {
					return _expr.isEmpty() ? _con.newInstance() : _con.newInstance(value);
				}
				if (_method != null) {
					return _expr.isEmpty() ? _method.invoke(null) : _method.invoke(null, value);
				}
				return value;
			} catch (InvocationTargetException e) {
				throw ReflectionUtils.convert(e.getCause(), Exception.class);
			}
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
