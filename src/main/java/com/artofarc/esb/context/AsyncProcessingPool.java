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
package com.artofarc.esb.context;

import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.artofarc.esb.action.Action;
import com.artofarc.esb.action.ThrowExceptionAction;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;

public final class AsyncProcessingPool implements Runnable {

	public final static class AsyncContext {
		public final Action nextAction;
		public final Collection<Action> executionStack;
		public final Map<String, Object> variables;
		public final long expiry;

		public AsyncContext(Action nextAction, Collection<Action> executionStack, Map<String, Object> variables, long expiry) {
			this.nextAction = nextAction;
			this.executionStack = executionStack;
			this.variables = variables;
			this.expiry = expiry;
		}
	}

	private final Map<Object, AsyncContext> _asyncContexts = new ConcurrentHashMap<>();
	private final WorkerPool _workerPool;

	private ScheduledFuture<?> _scheduledFuture;

	public AsyncProcessingPool(WorkerPool workerPool) {
		_workerPool = workerPool;
	}

	public int getPoolSize() {
		return _asyncContexts.size();
	}

	private synchronized void start() {
		if (_scheduledFuture == null) {
			_scheduledFuture = _workerPool.getScheduledExecutorService().scheduleWithFixedDelay(this, 30L, 30L, TimeUnit.SECONDS);
		}
	}

	synchronized void stop() {
		if (_scheduledFuture != null) {
			_scheduledFuture.cancel(true);
		}
	}

	public void putAsyncContext(Object correlationID, AsyncContext asyncContext) {
		if (_asyncContexts.putIfAbsent(correlationID, asyncContext) != null) {
			throw new IllegalArgumentException("correlationID already used: " + correlationID);
		}
		Context.logger.debug("AsyncContext put with correlationID " + correlationID + " expires " + new Date(asyncContext.expiry));
		start();
	}

	public AsyncContext removeAsyncContext(Object correlationID) {
		Context.logger.debug("AsyncContext removed with correlationID " + correlationID);
		return _asyncContexts.remove(correlationID);
	}

	@Override
	public void run() {
		for (Iterator<Map.Entry<Object, AsyncContext>> iter = _asyncContexts.entrySet().iterator(); iter.hasNext();) {
			Map.Entry<Object, AsyncContext> entry = iter.next();
			AsyncContext asyncContext = entry.getValue();
			if (asyncContext.expiry < System.currentTimeMillis()) {
				try {
					iter.remove();
					Context context = new Context(_workerPool.getPoolContext());
					context.getExecutionStack().addAll(asyncContext.executionStack);
					ESBMessage message = new ESBMessage(BodyType.INVALID, null);
					message.getVariables().putAll(asyncContext.variables);
					Action action = new ThrowExceptionAction("AsyncContext expired for correlationID " + entry.getKey());
					action.setNextAction(asyncContext.nextAction);
					action.process(context, message);
				} catch (Exception e) {
					Context.logger.info("Exception while expiring AsyncContext", e);
				}
			}
		}
	}

}
