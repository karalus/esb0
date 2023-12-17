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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.artofarc.esb.action.Action;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;

public final class AsyncProcessingPool implements Runnable {

	private static class AsyncContext implements Delayed {
		final Object correlationID;
		final Action nextAction;
		final Collection<Action> executionStack, stackErrorHandler;
		final Collection<Integer> stackPos;
		final Map<String, Object> variables;
		final long expiry;

		AsyncContext(Object correlationID, Action nextAction, Collection<Action> executionStack, Collection<Action> stackErrorHandler, Collection<Integer> stackPos, Map<String, Object> variables, long expiry) {
			this.correlationID = correlationID;
			this.nextAction = nextAction;
			this.executionStack = executionStack;
			this.stackErrorHandler = new ArrayList<>(stackErrorHandler);
			this.stackPos = new ArrayList<>(stackPos);
			this.variables = variables;
			this.expiry = expiry;
		}

		@Override
		public long getDelay(TimeUnit unit) {
			return unit.convert(expiry - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
		}

		@Override
		public int compareTo(Delayed other) {
			return Long.compare(expiry, ((AsyncContext) other).expiry);
		}
	}

	private final Map<Object, AsyncContext> _asyncContexts = new ConcurrentHashMap<>();
	private final DelayQueue<AsyncContext> _expiries = new DelayQueue<>();
	private final WorkerPool _workerPool;
	private volatile Future<?> _cleaner;

	public AsyncProcessingPool(WorkerPool workerPool) {
		_workerPool = workerPool;
	}

	public int getPoolSize() {
		return _asyncContexts.size();
	}

	private void start() {
		// double checked locking
		if (_cleaner == null) {
			synchronized (this) {
				if (_cleaner == null) {
					_cleaner = _workerPool.executeLongLived(this, "AsyncProcessingPool-" + _workerPool.getName());
				}
			}
		}
	}

	void stop() {
		Future<?> cleaner = _cleaner;
		if (cleaner != null) {
			cleaner.cancel(true);
		}
	}

	public void saveContext(Object correlationID, Action nextAction, Collection<Action> executionStack, Collection<Action> stackErrorHandler, Collection<Integer> stackPos, Map<String, Object> variables, long expiry) {
		if (nextAction == null && executionStack.isEmpty()) {
			throw new IllegalArgumentException("No action for resume given");
		}
		AsyncContext asyncContext = new AsyncContext(correlationID, nextAction, executionStack, stackErrorHandler, stackPos, variables, expiry);
		if (_asyncContexts.putIfAbsent(correlationID, asyncContext) != null) {
			throw new IllegalArgumentException("correlationID already used: " + correlationID);
		}
		_expiries.add(asyncContext);
		Context.logger.debug("AsyncContext put with correlationID " + correlationID + " expires " + new Date(asyncContext.expiry));
		start();
	}

	public Action restoreContext(Object correlationID, Context context, ESBMessage message) {
		AsyncContext asyncContext = _asyncContexts.remove(correlationID);
		if (asyncContext != null) {
			Context.logger.debug("AsyncContext removed with correlationID " + correlationID);
			context.getExecutionStack().addAll(asyncContext.executionStack);
			context.getStackErrorHandler().addAll(asyncContext.stackErrorHandler);
			context.getStackPos().addAll(asyncContext.stackPos);
			message.getVariables().putAll(asyncContext.variables);
			return asyncContext.nextAction != null ? asyncContext.nextAction : context.getExecutionStack().pop();
		}
		return null;
	}

	@Override
	public void run() {
		try {
			for (;;) {
				AsyncContext asyncContext = _asyncContexts.remove(_expiries.take().correlationID);
				if (asyncContext != null) {
					Context context = new Context(_workerPool.getPoolContext());
					context.getExecutionStack().addAll(asyncContext.executionStack);
					context.getStackErrorHandler().addAll(asyncContext.stackErrorHandler);
					if (context.getStackPos().addAll(asyncContext.stackPos)) {
						context.unwindStack();
					}
					ESBMessage message = new ESBMessage(BodyType.EXCEPTION, new TimeoutException("AsyncContext expired for correlationID " + asyncContext.correlationID));
					message.getVariables().putAll(asyncContext.variables);
					try {
						Action.processException(context, message);
					} catch (Exception e) {
						Context.logger.debug("Exception not been processed", e);
					}
				}
			}
		} catch (InterruptedException e) {
			// cancelled
		}
	}

}
