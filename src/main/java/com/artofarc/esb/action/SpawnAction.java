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

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.WorkerPoolThreadFactory;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.ESBVariableConstants;

public class SpawnAction extends Action {

	private final String _workerPool;

	private final boolean _usePipe, _join = false;

	public SpawnAction(String workerPool, boolean usePipe) {
		_workerPool = workerPool;
		_usePipe = usePipe;
		_pipelineStop = true;
	}

	@Override
	public Action nextAction(ExecutionContext execContext) {
		return null;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		if (_nextAction == null) {
			throw new com.artofarc.esb.action.ExecutionException(this, "nextAction not set");
		}
		Collection<Action> executionStack  = new ArrayList<>(context.getExecutionStack());
		context.getExecutionStack().clear();
		if (_usePipe) {
			PipedOutputStream pos = new PipedOutputStream();
			ESBMessage clone = message.clone();
			clone.reset(BodyType.INPUT_STREAM, new PipedInputStream(pos, ESBMessage.MTU));
			Future<ESBMessage> future = submit(context, clone, executionStack);
			if (inPipeline) {
				message.reset(BodyType.OUTPUT_STREAM, pos);
			} else {
				message.writeRawTo(pos, context);
			}
			return new ExecutionContext(pos, future);
		} else {
			ExecutionContext execContext = new ExecutionContext(executionStack);
			if (inPipeline) {
				ByteArrayOutputStream bos = new ByteArrayOutputStream(ESBMessage.MTU);
				message.reset(BodyType.OUTPUT_STREAM, bos);
				execContext.setResource2(bos);
			}
			return execContext;
		}
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, final ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		Future<ESBMessage> future;
		if (_usePipe) {
			PipedOutputStream pos = execContext.getResource();
			pos.close();
			message.reset(BodyType.INVALID, null);
			future = execContext.getResource2();
		} else {
			ByteArrayOutputStream bos = execContext.getResource2();
			if (bos != null) {
				message.reset(BodyType.BYTES, bos.toByteArray());
			} else {
				if (message.getBodyType() != BodyType.INVALID) {
					message.getBodyAsByteArray(context);
				}
			}
			future = submit(context, message, execContext.<Collection<Action>>getResource());
		}
		if (_join) {
			join(context, message, future);
		}
	}

	private Future<ESBMessage> submit(Context context, final ESBMessage message, Collection<Action> executionStack) throws RejectedExecutionException {
		String workerPool = message.getVariable(ESBVariableConstants.WorkerPool, _workerPool);
        return submit(context, message, workerPool, _nextAction, executionStack, _join);
	}

	public static Future<ESBMessage> submit(Context context, final ESBMessage message, String workerPool, final Action action, final Collection<Action> executionStack, boolean join) throws RejectedExecutionException {
		context.getTimeGauge().startTimeMeasurement();
		try {
			return context.getPoolContext().getGlobalContext().getWorkerPool(workerPool).getExecutorService().submit(new Callable<ESBMessage>() {

				@Override
				public ESBMessage call() throws Exception {
                    final Context workerContext = WorkerPoolThreadFactory.getContext();
                    // handover execution stack
                    workerContext.getExecutionStack().addAll(executionStack);
                    action.process(workerContext, message);
					return message;
				}
			});
		} finally {
			context.getTimeGauge().stopTimeMeasurement("Async submit", join);
		}
	}

	public static ESBMessage join(Context context, ESBMessage message, Future<ESBMessage> future) throws Exception {
		try {
			return future.get(message.getTimeleft(), TimeUnit.MILLISECONDS);
		} catch (ExecutionException e) {
			throw (Exception) e.getCause();
		} finally {
			context.getTimeGauge().stopTimeMeasurement("Async join", false);
		}
	}

}
