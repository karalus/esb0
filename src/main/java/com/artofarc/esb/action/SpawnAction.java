/*
 * Copyright 2021 Andre Karalus
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

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Collection;
import java.util.Collections;
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
import com.artofarc.util.ByteArrayOutputStream;
import com.artofarc.util.DataStructures;
import com.artofarc.util.IOUtils;
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
		Collection<Action> executionStack = DataStructures.moveToNewList(context.getExecutionStack());
		if (_usePipe) {
			PipedOutputStream pos = new PipedOutputStream();
			ESBMessage clone = message.clone();
			clone.reset(BodyType.INPUT_STREAM, new PipedInputStream(pos, IOUtils.MTU));
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
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				message.reset(BodyType.OUTPUT_STREAM, bos);
				execContext.setResource2(bos);
			}
			return execContext;
		}
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		Future<ESBMessage> future;
		if (_usePipe) {
			execContext.<PipedOutputStream> getResource().close();
			message.reset(BodyType.INVALID, null);
			future = execContext.getResource2();
		} else {
			ByteArrayOutputStream bos = execContext.getResource2();
			if (bos != null) {
				message.reset(BodyType.INPUT_STREAM, bos.getByteArrayInputStream());
			} else {
				if (message.getBodyType() != BodyType.INVALID) {
					message.getBodyAsByteArray(context);
				}
			}
			future = submit(context, message, execContext.<Collection<Action>> getResource());
		}
		if (_join) {
			try {
				future.get(message.getTimeleft(60000L).longValue(), TimeUnit.MILLISECONDS);
			} catch (java.util.concurrent.ExecutionException e) {
				throw ReflectionUtils.convert(e.getCause(), Exception.class);
			} finally {
				context.getTimeGauge().stopTimeMeasurement("Async join", false);
			}
		}
	}

	private Future<ESBMessage> submit(Context context, final ESBMessage message, final Collection<Action> executionStack) throws RejectedExecutionException {
		context.getTimeGauge().startTimeMeasurement();
		String workerPoolName = message.getVariable(ESBConstants.WorkerPool, _workerPool);
		final WorkerPool workerPool = context.getGlobalContext().getWorkerPool(workerPoolName);
		final Collection<Action> stackErrorHandler = _join ? Collections.emptyList() : DataStructures.moveToNewList(context.getStackErrorHandler());
		final Collection<Integer> stackPos = _join ? Collections.emptyList() : DataStructures.moveToNewList(context.getStackPos());
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
						_nextAction.process(workerContext, message);
					} finally {
						workerContext.getExecutionStack().clear();
						workerContext.getStackErrorHandler().clear();
						workerContext.getStackPos().clear();
						workerPool.releaseContext(workerContext);
					}
					return message;
				}
			});
		} finally {
			context.getTimeGauge().stopTimeMeasurement("Async submit", _join);
		}
	}

}
