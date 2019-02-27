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

	public final Map<String, Action> getBranchMap() {
		return _branchMap;
	}

	@Override
	protected boolean isPipelineStop() {
		boolean pipelineStop = _defaultAction != null ? _defaultAction.isPipelineStop() : _nextAction == null || _nextAction.isPipelineStop();
		for (Action action : _branchMap.values()) {
			if (action != null && (pipelineStop |= action.isPipelineStop())) {
				break;
			}
		}
		return pipelineStop;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		Object value = resolve(message, _varName, true);
		Action action = null;
		if (value != null) {
			if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
				throw new ExecutionException(this, "Value for " + _varName + " is not an atomic type: " + value.getClass());
			}
			if (_branchMap.containsKey(value.toString())) {
				action = _branchMap.get(value.toString());
			} else {
				action = _defaultAction;
			}
		}
		if (_nextAction != null) {
			context.getExecutionStack().push(_nextAction);
		}
		return new ExecutionContext(action);
	}

	@Override
	protected Action nextAction(ExecutionContext execContext) {
		return execContext.getResource();
	}

}
