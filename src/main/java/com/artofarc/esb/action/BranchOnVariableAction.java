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

import java.util.HashMap;
import java.util.Map;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.ESBMessage;

public class BranchOnVariableAction extends Action {

	private final String _varName;
	private final Map<String, Action> _branchMap = new HashMap<>();
	private final Action _defaultAction;

	public BranchOnVariableAction(String varName, Action defaultAction) {
		_varName = varName;
		_defaultAction = defaultAction;
	}

	public Map<String, Action> getBranchMap() {
		return _branchMap;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		Object value = message.getVariable(_varName);
		if (value == null) {
			value = message.getHeader(_varName);
		}
		Action action = null;
		if (value != null) {
			if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
				throw new ExecutionException(this, "Value for variable " + _varName + " is not an atomic type: " + value.getClass());
			}
			action = _branchMap.get(value.toString());
			if (action == null) {
				action = _defaultAction;
			}
		}
		return new ExecutionContext(action);
	}

	@Override
	protected Action nextAction(ExecutionContext execContext) {
		Action action = execContext.getResource();
		return action != null ? action : super.nextAction(execContext);
	}

}
