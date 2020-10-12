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

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.XQuerySource;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.service.XQDecl;

public class AssignAction extends TransformAction {

	private final boolean _clearAll;

	public AssignAction(String varName, String expression, Collection<Map.Entry<String, String>> namespaces, List<XQDecl> bindNames, String contextItem) {
		this(java.util.Collections.singletonList(new Assignment(varName, false, expression, false, null)), null, namespaces, bindNames, contextItem, false);
	}

	public AssignAction(List<Assignment> assignments, String bodyExpr, Collection<Map.Entry<String, String>> namespaces, List<XQDecl> bindNames, String contextItem, boolean clearAll) {
		super(createXQuery(assignments, namespaces, bindNames, bodyExpr != null ? bodyExpr : "."), assignments, null, contextItem);
		_clearAll = clearAll;
		_bindNames = bindNames;
	}

	private static XQuerySource createXQuery(List<Assignment> assignments, Collection<Map.Entry<String, String>> namespaces, List<XQDecl> bindNames, String bodyExpr) {
		StringBuilder builder = new StringBuilder();
		if (namespaces != null) {
			for (Map.Entry<String, String> entry : namespaces) {
				builder.append("declare namespace ").append(entry.getKey()).append("=\"").append(entry.getValue()).append("\";\n");
			}
		}
		HashSet<String> variables = new HashSet<String>();
		for (XQDecl bindName : bindNames) {
			builder.append("declare variable $").append(bindName.getValue());
			if (bindName.getType() != null) {
				builder.append(" as ").append(bindName.getType());
			}
			builder.append(" external;\n");
			variables.add("$" + bindName.getValue());
		}
		boolean flwor = false;
		for (Assignment assignment : assignments) {
			if (createLet(assignment, variables)) {
				builder.append("let $").append(assignment.name).append(" := ").append(assignment.expr).append('\n');
				flwor = true;
			}
		}
		if (flwor) builder.append("return ");
		builder.append('(');
		for (Assignment assignment : assignments) {
			boolean hasAtomicType = assignment.type != null && assignment.type.startsWith("xs:");
			if (assignment.nullable) {
				builder.append("if ($").append(assignment.name).append(") then (true(), ");
				if (hasAtomicType) builder.append(assignment.type).append('(');
				builder.append('$').append(assignment.name);
				if (hasAtomicType) builder.append(')');
				builder.append(") else false()");
			} else {
				if (hasAtomicType) builder.append(assignment.type).append('(');
				if (createLet(assignment, variables)) {
					builder.append('$').append(assignment.name);
				} else {
					builder.append(assignment.expr);
				}
				if (hasAtomicType) builder.append(')');
			}
			builder.append(", ");
			assignment.expr = null;
			assignment.type = null;
		}
		builder.append(bodyExpr).append(')');
		return XQuerySource.create(builder.toString());
	}

	private static boolean createLet(Assignment assignment, HashSet<String> variables) {
		return assignment.name != null && !variables.contains("$" + assignment.name)
				&& !variables.contains(assignment.expr) && !assignment.expr.equals(".");
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		if (_clearAll) {
			message.clearHeaders();
		}
		return super.prepare(context, message, inPipeline);
	}

}
