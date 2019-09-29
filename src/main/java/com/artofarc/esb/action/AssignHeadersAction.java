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
import java.util.List;
import java.util.Map;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.service.XQDecl;

public class AssignHeadersAction extends AssignAction {

	private final boolean _clearAll;

	public AssignHeadersAction(List<Assignment> assignments, Collection<Map.Entry<String, String>> namespaces, List<XQDecl> bindNames, String contextItem, boolean clearAll) {
		super(assignments, namespaces, bindNames, contextItem);
		_clearAll = clearAll;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		final Map<String, Object> headers = message.getHeaders();
		if (_clearAll) {
			headers.clear();
		}
		return prepare(context, message, headers);
	}

}
