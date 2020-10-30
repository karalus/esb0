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

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.WorkerPool;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;

public class ForkAction extends Action {

	private final String _workerPool;
	private final boolean _copyMessage, _copyHeaders;
	private final Action _fork;

	public ForkAction(String workerPool, boolean copyMessage, boolean copyHeaders, Action fork) {
		_pipelineStop = true;
		_workerPool = workerPool;
		_copyMessage = copyMessage;
		_copyHeaders = copyHeaders;
		_fork = fork;
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		final WorkerPool workerPool = context.getGlobalContext().getWorkerPool(_workerPool);
		final Context workerContext = workerPool.getContext();
		try {
			final ESBMessage copy = message.copy(context, workerContext, _copyMessage, _copyHeaders);
			workerPool.getExecutorService().execute(new Runnable() {

				@Override
				public void run() {
					try {
						copy.putVariableIfNotNull(ESBConstants.timeleftOrigin, copy.getVariables().remove(ESBConstants.timeleft));
						_fork.process(workerContext, copy);
					} catch (Exception e) {
						logger.error("Exception in forked action pipeline", e);
					} finally {
						workerPool.releaseContext(workerContext);
					}
				}
			});
		} catch (Exception e) {
			workerPool.releaseContext(workerContext);
			throw e;
		}
	}

}
