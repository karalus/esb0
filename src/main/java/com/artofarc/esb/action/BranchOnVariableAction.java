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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.ESBMessage;

public class BranchOnVariableAction extends Action {

	private final String _varName;
	private final Map<Object, Action> _branchMap = new LinkedHashMap<>();
	private final Action _defaultAction, _nullAction;
	private boolean _useRegEx;

	public BranchOnVariableAction(String varName, Action defaultAction, Action nullAction) {
		_varName = varName;
		_defaultAction = defaultAction;
		_nullAction = nullAction;
	}

	public final void addBranch(String value, Action action) {
		if (_branchMap.put(value, action) != null) {
			throw new IllegalArgumentException("Duplicate branch value " + value);
		}
	}

	public final void addBranchRegEx(String regEx, Action action) {
		_branchMap.put(Pattern.compile(regEx), action);
		_useRegEx = true;
	}

	@Override
	protected boolean isPipelineStop() {
		boolean pipelineStop = _nextAction == null || _nextAction.isPipelineStop();
		if (_defaultAction != null) {
			pipelineStop |= _defaultAction.isPipelineStop();
		}
		if (_nullAction != null) {
			pipelineStop |= _nullAction.isPipelineStop();
		}
		if (!pipelineStop) {
			for (Action action : _branchMap.values()) {
				if (action != null && (pipelineStop |= action.isPipelineStop())) {
					break;
				}
			}
		}
		return pipelineStop;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		Object value = resolve(message, _varName, true);
		Action action = _nullAction;
		if (value != null) {
			checkAtomic(value, _varName);
			String strValue = value.toString();
			action = _defaultAction;
			if (_branchMap.containsKey(strValue)) {
				action = _branchMap.get(strValue);
			} else if (_useRegEx) {
				for (Map.Entry<Object, Action> entry : _branchMap.entrySet()) {
					if (entry.getKey() instanceof Pattern) {
						Pattern pattern = (Pattern) entry.getKey();
						Matcher matcher = pattern.matcher(strValue);
						if (matcher.matches()) {
							action = entry.getValue();
							for (int i = 1; i <= matcher.groupCount(); ++i) {
								message.getVariables().put(_varName + '#' + i, matcher.group(i));
							}
							break;
						}
					}
				}
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
