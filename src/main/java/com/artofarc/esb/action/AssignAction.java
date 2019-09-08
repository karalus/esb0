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
import com.artofarc.util.Collections;

public class AssignAction extends TransformAction {

	public AssignAction(String varName, String expression, Collection<Map.Entry<String, String>> namespaces, List<String> bindNames, String contextItem) {
		this(java.util.Collections.singletonList(Collections.createEntry(Collections.createEntry(varName, false), expression)), namespaces, bindNames, contextItem);
	}

	public AssignAction(Collection<Map.Entry<Map.Entry<String, Boolean>, String>> assignments, Collection<Map.Entry<String, String>> namespaces, List<String> bindNames, String contextItem) {
		this(new ArrayList<Map.Entry<String, Boolean>>(), assignments, namespaces, bindNames, contextItem);
	}

	private AssignAction(Collection<Map.Entry<String, Boolean>> varNames, Collection<Map.Entry<Map.Entry<String, Boolean>, String>> assignments, Collection<Map.Entry<String, String>> namespaces, List<String> bindNames, String contextItem) {
		super(createXQuery(assignments, namespaces, varNames, bindNames), varNames, null, contextItem);
		_bindNames = bindNames;
	}

	private static XQuerySource createXQuery(Collection<Map.Entry<Map.Entry<String, Boolean>, String>> assignments, Collection<Map.Entry<String, String>> namespaces, Collection<Map.Entry<String, Boolean>> varNames, List<String> bindNames) {
		StringBuilder builder = new StringBuilder();
		if (namespaces != null) {
			for (Map.Entry<String, String> entry : namespaces) {
				builder.append("declare namespace ").append(entry.getKey()).append("=\"").append(entry.getValue()).append("\";\n");
			}
		}
		for (String bindName : bindNames) {
			builder.append("declare variable $").append(bindName).append(" external;\n");
		}
		builder.append("(");
		for (Map.Entry<Map.Entry<String, Boolean>, String> entry : assignments) {
			Map.Entry<String, Boolean> var = entry.getKey();
			varNames.add(var);
			boolean nullable = entry.getKey().getValue();
			if (nullable) {
				String varName = entry.getKey().getKey();
				builder.append("let $").append(varName).append(" := ").append(entry.getValue()).append(" return if ($").append(varName).append(") then (true(), $").append(varName).append(") else false(), ");
			} else {
				builder.append(entry.getValue()).append(", ");
			}
		}
		builder.append(".)");
		return XQuerySource.create(builder.toString());
	}

}
