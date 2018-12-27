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

import java.util.logging.Level;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.WorkerPoolThreadFactory;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.ESBVariableConstants;

public class ForkAction extends Action {

	private final String _workerPool;
	private final boolean _copyMessage;

	private Action _fork;

	public ForkAction(String workerPool, boolean copyMessage) {
		_pipelineStop = true;
		_workerPool = workerPool;
		_copyMessage = copyMessage;
	}

	public void setFork(Action fork) {
		_fork = fork;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) {
		if (inPipeline) {
			message.reset(BodyType.INVALID, null);
		}
		return null;
	}

	@Override
	protected void execute(Context context, ExecutionContext resource, final ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		final ESBMessage copy = message.copy(context, _copyMessage);
		context.getPoolContext().getGlobalContext().getWorkerPool(_workerPool).getExecutorService().execute(new Runnable() {

			@Override
			public void run() {
				try {
					copy.putVariable(ESBVariableConstants.timeleftOrigin, copy.getVariables().remove(ESBVariableConstants.timeleft));
					_fork.process(WorkerPoolThreadFactory.getContext(), copy);
				} catch (Exception e) {
					logger.log(Level.SEVERE, "Exception in forked action pipeline", e);
				}
			}
		});
	}

}
