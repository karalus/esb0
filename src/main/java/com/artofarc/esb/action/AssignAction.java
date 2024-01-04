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

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.service.XQDecl;
import com.artofarc.util.XQuerySource;

public class AssignAction extends TransformAction {

	private final boolean _clearHeaders;

	public AssignAction(String varName, String expression, Collection<Map.Entry<String, String>> namespaces, List<XQDecl> bindNames, String contextItem) {
		this(Collections.singletonList(new Assignment(varName, false, expression, false, null)), false, null, namespaces, bindNames, contextItem, false);
	}

	public AssignAction(List<Assignment> assignments, boolean doNullCheck, String bodyExpr, Collection<Map.Entry<String, String>> namespaces, List<XQDecl> bindNames, String contextItem, boolean clearHeaders) {
		super(createXQuery(assignments, doNullCheck, namespaces, bindNames, bodyExpr != null ? bodyExpr : contextItem != null ? null : "."), createCheckNotNull(bindNames), assignments,	doNullCheck, bodyExpr != null, null, contextItem, null);
		if (contextItem != null && bodyExpr != null) {
			throw new IllegalArgumentException("when a contextItem is used the body cannot be assigned");
		}
		_clearHeaders = clearHeaders;
	}

	private static XQuerySource createXQuery(List<Assignment> assignments, boolean doNullCheck, Collection<Map.Entry<String, String>> namespaces, List<XQDecl> bindNames, String bodyExpr) {
		StringBuilder builder = new StringBuilder();
		if (namespaces != null) {
			for (Map.Entry<String, String> entry : namespaces) {
				builder.append("declare namespace ").append(entry.getKey()).append("=\"").append(entry.getValue()).append("\";\n");
			}
		}
		HashSet<String> variables = new HashSet<>();
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
				assignment.expr = "$" + assignment.name;
				flwor = true;
			}
		}
		if (flwor) builder.append("return ");
		builder.append('(');
		for (Iterator<Assignment> iter = assignments.iterator(); iter.hasNext();) {
			Assignment assignment = iter.next();
			boolean hasAtomicType = assignment.type != null && !assignment.list && assignment.type.startsWith("xs:");
			if (doNullCheck || assignment.nullable || assignment.list) {
				builder.append("count(").append(assignment.expr).append("), ");
			}
			if (hasAtomicType) builder.append(assignment.type).append('(');
			builder.append(assignment.expr);
			if (hasAtomicType) builder.append(')');
			if (iter.hasNext()) {
				builder.append(", ");
			}
			// save some bytes memory
			assignment.expr = null;
		}
		if (bodyExpr != null) {
			builder.append(", ").append(bodyExpr);
		}
		builder.append(')');
		return XQuerySource.create(builder.toString());
	}

	private static boolean createLet(Assignment assignment, HashSet<String> variables) {
		return assignment.name != null && !variables.contains("$" + assignment.name)
				&& !variables.contains(assignment.expr) && !assignment.expr.equals(".");
	}

	private static HashSet<String> createCheckNotNull(List<XQDecl> bindNames) {
		HashSet<String> result = null;
		for (XQDecl bindName : bindNames) {
			if (!bindName.isNullable()) {
				if (result == null) {
					result = new HashSet<>();					
				}
				result.add(bindName.getValue());
			}
		}
		return result;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		if (_clearHeaders) {
			message.clearHeaders();
		}
		return super.prepare(context, message, inPipeline);
	}

}
