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
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.artofarc.esb.context.XQuerySource;
import com.artofarc.esb.service.XQDecl;

public class AssignAction extends TransformAction {

	public final static class Assignment {
		final String name;
		final String value;
		final boolean nullable;
		final String type;

		public Assignment(String name, String value, boolean nullable, String type) {
			this.name = name;
			this.value = value;
			this.nullable = nullable;
			this.type = type;
		}
	}

	public AssignAction(String varName, String expression, Collection<Map.Entry<String, String>> namespaces, List<XQDecl> bindNames, String contextItem) {
		this(java.util.Collections.singletonList(new Assignment(varName, expression, false, null)), namespaces, bindNames, contextItem);
	}

	public AssignAction(List<Assignment> assignments, Collection<Map.Entry<String, String>> namespaces, List<XQDecl> bindNames, String contextItem) {
		this(new ArrayList<Map.Entry<String, Boolean>>(), assignments, namespaces, bindNames, contextItem);
	}

	private AssignAction(List<Map.Entry<String, Boolean>> varNames, List<Assignment> assignments, Collection<Map.Entry<String, String>> namespaces, List<XQDecl> bindNames, String contextItem) {
		super(createXQuery(assignments, namespaces, varNames, bindNames), varNames, null, contextItem);
		_bindNames = bindNames;
	}

	private static XQuerySource createXQuery(List<Assignment> assignments, Collection<Map.Entry<String, String>> namespaces, List<Map.Entry<String, Boolean>> varNames, List<XQDecl> bindNames) {
		StringBuilder builder = new StringBuilder();
		if (namespaces != null) {
			for (Map.Entry<String, String> entry : namespaces) {
				builder.append("declare namespace ").append(entry.getKey()).append("=\"").append(entry.getValue()).append("\";\n");
			}
		}
		for (XQDecl bindName : bindNames) {
			builder.append("declare variable $").append(bindName.getValue());
			if (bindName.getType() != null) {
				builder.append(" as ").append(bindName.getType());
			}
			builder.append(" external;\n");
		}
		builder.append("(");
		for (Assignment assignment : assignments) {
			varNames.add(com.artofarc.util.Collections.createEntry(assignment.name, assignment.nullable));
			boolean hasAtomicType = assignment.type != null && assignment.type.startsWith("xs:");
			if (assignment.nullable) {
				builder.append("let $").append(assignment.name).append(" := ").append(assignment.value).append(" return if ($").append(assignment.name).append(") then (true(), ");
				if (hasAtomicType) builder.append(assignment.type).append('(');
				builder.append("$").append(assignment.name);
				if (hasAtomicType) builder.append(')');
				builder.append(") else false()");
			} else {
				if (hasAtomicType) builder.append(assignment.type).append('(');
				builder.append(assignment.value);
				if (hasAtomicType) builder.append(')');
			}
			builder.append(", ");
		}
		builder.append(".)");
		return XQuerySource.create(builder.toString());
	}

}
