/*
 * Copyright 2023 Andre Karalus
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
import java.util.List;
import java.util.Map;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.service.XQDecl;

public class ConditionalAction extends AssignAction {

	private static final String varName = "resultOfCondition";

	private final Action _conditionalAction;
	private final boolean _proceed;

	public ConditionalAction(List<Assignment> assignments, String bodyExpr, Collection<Map.Entry<String, String>> namespaces, List<XQDecl> bindNames, String baseURI, String contextItem, boolean clearHeaders, String expression, Action conditionalAction, boolean proceed) {
		super(assignments.add(new Assignment(varName, false, expression, Boolean.FALSE, null)) ? assignments : null, bodyExpr, namespaces, bindNames, baseURI, contextItem, clearHeaders);
		_conditionalAction = conditionalAction;
		_proceed = proceed;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		ExecutionContext executionContext = super.prepare(context, message, inPipeline);
		executionContext.setResource3(message.removeVariable(varName));
		if (_proceed && _nextAction != null) {
			context.getExecutionStack().push(_nextAction);
		}
		return executionContext;
	}

	@Override
	protected Action nextAction(ExecutionContext execContext) {
		return execContext.<Boolean> getResource3() ? _conditionalAction : _proceed ? null : _nextAction;
	}

}
