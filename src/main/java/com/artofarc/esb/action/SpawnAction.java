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
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.WorkerPool;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.util.Collections;
import com.artofarc.util.ReflectionUtils;

public class SpawnAction extends Action {

	private final String _workerPool;
	private final boolean _usePipe, _join;

	public SpawnAction(String workerPool, boolean usePipe, boolean join) {
		_workerPool = workerPool;
		_usePipe = usePipe;
		_join = join;
		_pipelineStop = true;
	}

	@Override
	public Action nextAction(ExecutionContext execContext) {
		return null;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		if (_nextAction == null) {
			throw new ExecutionException(this, "nextAction not set");
		}
		Collection<Action> executionStack = Collections.newList(context.getExecutionStack(), false, true);
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
			execContext.<PipedOutputStream> getResource().close();
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
			future = submit(context, message, execContext.<Collection<Action>> getResource());
		}
		if (_join) {
			join(context, message, future, _join);
		}
	}

	private Future<ESBMessage> submit(Context context, ESBMessage message, Collection<Action> executionStack) throws RejectedExecutionException {
		String workerPool = message.getVariable(ESBConstants.WorkerPool, _workerPool);
		return submit(context, message, workerPool, _nextAction, executionStack, _join, false);
	}

	public static Future<ESBMessage> submit(Context context, final ESBMessage message, String workerPoolName, final Action action, final Collection<Action> executionStack, boolean rejoin, final boolean spread) throws RejectedExecutionException {
		context.getTimeGauge().startTimeMeasurement();
		final WorkerPool workerPool = context.getPoolContext().getGlobalContext().getWorkerPool(workerPoolName);
		final Collection<Action> stackErrorHandler = Collections.newList(context.getStackErrorHandler(), rejoin, !spread);
		final Collection<Integer> stackPos = Collections.newList(context.getStackPos(), rejoin, !spread);
		try {
			return workerPool.getExecutorService().submit(new Callable<ESBMessage>() {

				@Override
				public ESBMessage call() throws Exception {
					final Context workerContext = workerPool.getContext();
					// hand over execution stack
					workerContext.getExecutionStack().addAll(executionStack);
					workerContext.getStackErrorHandler().addAll(stackErrorHandler);
					workerContext.getStackPos().addAll(stackPos);
					try {
						action.process(workerContext, message);
						return message;
					} finally {
						if (spread) {
							// remove copies
							workerContext.getStackPos().removeAll(stackPos);
							workerContext.getStackErrorHandler().removeAll(stackErrorHandler);
						}
						workerPool.releaseContext(workerContext);
					}
				}
			});
		} finally {
			context.getTimeGauge().stopTimeMeasurement("Async submit", rejoin);
		}
	}

	public static ESBMessage join(Context context, ESBMessage message, Future<ESBMessage> future, boolean rejoin) throws Exception {
		try {
			return future.get(message.getTimeleft(60000L).longValue(), TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.ExecutionException e) {
			throw ReflectionUtils.convert(e.getCause(), Exception.class);
		} finally {
			if (rejoin) {
				context.getTimeGauge().stopTimeMeasurement("Async join", false);
			}
		}
	}

}
