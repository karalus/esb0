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

import com.artofarc.util.Collections;

public class AssignAction extends TransformAction {

	public AssignAction(String varName, String expression) {
		this(varName, expression, null, java.util.Collections.<String> emptyList(), null);
	}

	public AssignAction(String varName, String expression, Collection<Map.Entry<String, String>> namespaces, List<String> bindNames, String contextItem) {
		this(java.util.Collections.singletonList(Collections.createEntry(varName, expression)), namespaces, bindNames, contextItem);
	}

	public AssignAction(Collection<Map.Entry<String, String>> assignments, Collection<Map.Entry<String, String>> namespaces, List<String> bindNames, String contextItem) {
		this(new ArrayList<String>(), assignments, namespaces, bindNames);
		_contextItem = contextItem;
		_pipelineStop = contextItem != null;
	}

	private AssignAction(ArrayList<String> varNames, Collection<Map.Entry<String, String>> assignments, Collection<Map.Entry<String, String>> namespaces,
			List<String> bindNames) {
		super(createQuery(assignments, namespaces, varNames, bindNames), varNames);
		_bindNames = bindNames;
	}

	private static String createQuery(Collection<Map.Entry<String, String>> assignments, Collection<Map.Entry<String, String>> namespaces,
			List<String> varNames, List<String> bindNames) {
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
		for (Map.Entry<String, String> entry : assignments) {
			varNames.add(entry.getKey());
			builder.append(entry.getValue()).append(", ");
		}
		builder.append(".)");
		return builder.toString();
	}

}
