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

	private final boolean _usePipe;

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
		if (_usePipe) {
			PipedOutputStream pos = new PipedOutputStream();
			PipedInputStream pis = new PipedInputStream(pos, ESBMessage.MTU);
			ESBMessage clone = message.clone();
			clone.reset(BodyType.INPUT_STREAM, pis);
			Future<Void> future = submit(context, clone);
			if (inPipeline) {
				message.reset(BodyType.OUTPUT_STREAM, pos);
			} else {
				message.writeTo(pos, context);
			}
			message.setTerminal(null);
			return new ExecutionContext(pos, future);
		} else {
			ExecutionContext execContext = new ExecutionContext(message.getTerminal());
			message.setTerminal(null);
			if (inPipeline) {
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				message.reset(BodyType.OUTPUT_STREAM, bos);
				execContext.setResource2(bos);
			}
			return execContext;
		}
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, final ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		Future<Void> future;
		if (_usePipe) {
			PipedOutputStream pos = execContext.getResource();
			pos.close();
			message.reset(BodyType.INVALID, null);
			future = execContext.getResource2();
		} else {
			message.setTerminal(execContext.<Action> getResource());
			ByteArrayOutputStream bos = execContext.getResource2();
			if (bos != null) {
				message.reset(BodyType.BYTES, bos.toByteArray());
			} else {
				if (message.getBodyType() != BodyType.INVALID) {
					message.getBodyAsByteArray(context);
				}
			}
			future = submit(context, message);
		}
		if (message.isJoin()) {
			join(context, message, future);
		}
	}

	private Future<Void> submit(Context context, final ESBMessage message) throws RejectedExecutionException {
		String workerPool = message.getVariable(ESBVariableConstants.WorkerPool, _workerPool);
		return submit(context, message, workerPool, _nextAction);
	}

	public static Future<Void> submit(Context context, final ESBMessage message, String workerPool, final Action action) throws RejectedExecutionException {
		context.getTimeGauge().startTimeMeasurement();
		try {
			return context.getPoolContext().getGlobalContext().getWorkerPool(workerPool).getExecutorService().submit(new Callable<Void>() {

				@Override
				public Void call() throws Exception {
					action.process(WorkerPoolThreadFactory.getContext(), message);
					return null;
				}
			});
		} finally {
			context.getTimeGauge().stopTimeMeasurement("Async submit", message.isJoin());
		}
	}

	public static void join(Context context, ESBMessage message, Future<Void> future) throws Exception {
		try {
			future.get(message.getTimeleft(), TimeUnit.MILLISECONDS);
		} catch (ExecutionException e) {
			throw (Exception) e.getCause();
		} finally {
			context.getTimeGauge().stopTimeMeasurement("Async join", false);
		}
	}

}
