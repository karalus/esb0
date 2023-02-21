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

import com.artofarc.esb.context.AsyncProcessingPool;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.ESBMessage;

public class ResumeAction extends Action {

	private final String _workerPool;
	private final String _correlationID;

	public ResumeAction(String workerPool, String correlationID) {
		_workerPool = workerPool;
		_correlationID = correlationID;
		_pipelineStop = true;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		Object correlationID = resolve(message, _correlationID, true);
		if (correlationID == null) {
			throw new ExecutionException(this, "name could not be resolved: " + _correlationID);
		}
		AsyncProcessingPool asyncProcessingPool = context.getGlobalContext().getWorkerPool(_workerPool).getAsyncProcessingPool();
		if (asyncProcessingPool == null) {
			throw new ExecutionException(this, "No AsyncProcessingPool in WorkerPool " + _workerPool);
		}
		Action action = asyncProcessingPool.restoreContext(correlationID, context, message);
		if (action == null) {
			throw new ExecutionException(this, "No AsyncContext found for " + correlationID);
		}
		return new ExecutionContext(action);		
	}

	@Override
	protected Action nextAction(ExecutionContext execContext) {
		return execContext.getResource();
	}

}
