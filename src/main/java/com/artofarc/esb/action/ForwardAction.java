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

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.ESBMessage;

public abstract class ForwardAction extends Action {

	@Override
	protected boolean isPipelineStop() {
		return _pipelineStop || _nextAction == null || _nextAction.isPipelineStop();
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		if (inPipeline && !_pipelineStop) {
			Action nextAction;
			if (_nextAction != null) {
				nextAction = _nextAction.isPipelineStop() ? _nextAction : null;
			} else {
				nextAction = context.getExecutionStack().poll();
				if (nextAction != null && !nextAction.isPipelineStop()) {
					// We cannot forward pipeline for more than one hop because isPipelineStop() will return false
					context.getExecutionStack().push(nextAction);
					nextAction = null;
				}
			}
			if (nextAction != null) {
				return new ExecutionContext(nextAction, nextAction.prepare(context, message, inPipeline));
			}
		}
		return null;
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		if (execContext != null) {
			Action nextAction = execContext.getResource();
			ExecutionContext nextContext = execContext.getResource2();
			nextAction.execute(context, nextContext, message, nextActionIsPipelineStop);
		}
	}

	@Override
	protected void close(Context context, ExecutionContext execContext, boolean exception) throws Exception {
		if (execContext != null) {
			Action nextAction = execContext.getResource();
			ExecutionContext nextContext = execContext.getResource2();
			nextAction.close(context, nextContext, exception);
		}
	}

	@Override
	protected Action nextAction(ExecutionContext execContext) {
		if (execContext != null) {
			Action nextAction = execContext.getResource();
			ExecutionContext nextContext = execContext.getResource2();
			return nextAction.nextAction(nextContext);
		}
		return _nextAction;
	}

}
