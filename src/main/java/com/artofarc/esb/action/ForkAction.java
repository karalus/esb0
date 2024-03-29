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

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.WorkerPool;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;

public class ForkAction extends Action {

	private final String _workerPool;
	private final boolean _copyMessage, _copyHeaders, _copyAttachments;
	private final Action _fork;

	public ForkAction(String workerPool, boolean copyMessage, boolean copyHeaders, boolean copyAttachments, Action fork) {
		_pipelineStop = true;
		_workerPool = workerPool;
		_copyMessage = copyMessage;
		_copyHeaders = copyHeaders;
		_copyAttachments = copyAttachments;
		_fork = fork;
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		final WorkerPool workerPool = context.getGlobalContext().getWorkerPool(_workerPool);
		final ESBMessage copy = message.copy(context, _copyMessage, _copyHeaders, _copyAttachments);
		workerPool.getExecutorService().execute(new Runnable() {

			@Override
			public void run() {
				Context workerContext = workerPool.getContext();
				try {
					copy.putVariableIfNotNull(ESBConstants.timeleftOrigin, copy.getVariables().remove(ESBConstants.timeleft));
					_fork.process(workerContext, copy);
				} catch (Exception e) {
					logger.error("Exception in forked action pipeline", e);
				} finally {
					workerContext.getExecutionStack().clear();
					workerContext.getStackErrorHandler().clear();
					workerContext.getStackPos().clear();
					workerPool.releaseContext(workerContext);
				}
			}
		});
	}

}
