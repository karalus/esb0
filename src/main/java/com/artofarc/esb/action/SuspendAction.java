/*
 * Copyright 2022 Andre Karalus
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

import java.util.ArrayList;

import com.artofarc.esb.context.AsyncProcessingPool;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.WorkerPool;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.DataStructures;

public class SuspendAction extends Action {

	private final String _correlationID;
	private final Long _timeout;

	public SuspendAction(String correlationID, long timeout) {
		_correlationID = correlationID;
		_timeout = timeout;
		_pipelineStop = true;
	}

	@Override
	public Action nextAction(ExecutionContext execContext) {
		return null;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) {
		return new ExecutionContext(DataStructures.moveToNewList(context.getExecutionStack()));
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		Object correlationID = eval(_correlationID, context, message);
		WorkerPool workerPool = context.getPoolContext().getWorkerPool();
		AsyncProcessingPool asyncProcessingPool = workerPool.getAsyncProcessingPool();
		if (asyncProcessingPool == null) {
			throw new ExecutionException(this, "No AsyncProcessingPool in WorkerPool " + workerPool.getName());
		}
		asyncProcessingPool.saveContext(correlationID, _nextAction, execContext.getResource(), new ArrayList<>(context.getStackErrorHandler()),
				message.getVariables(), message.<Long>getVariable(ESBConstants.initialTimestamp) + message.getTimeleft(_timeout).longValue());
	}

}
