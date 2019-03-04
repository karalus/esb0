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
package com.artofarc.esb.context;

import java.util.Collection;
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

	public static class AsyncContext {
		public Action nextAction;
		public Collection<Action> executionStack;
		public Map<String, Object> variables;
		public long expriry;
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

	public synchronized void stop() {
		if (_scheduledFuture != null) {
			_scheduledFuture.cancel(true);
		}
	}

	public void putAsyncContext(Object correlationID, AsyncContext asyncContext) {
		if (_asyncContexts.put(correlationID, asyncContext) != null) {
			throw new IllegalArgumentException("correlationID already used: " + correlationID);
		}
		start();
	}

	public AsyncContext removeAsyncContext(Object correlationID) {
		return _asyncContexts.remove(correlationID);
	}

	@Override
	public void run() {
		for (Iterator<Map.Entry<Object, AsyncContext>> iter = _asyncContexts.entrySet().iterator(); iter.hasNext();) {
			Map.Entry<Object, AsyncContext> entry = iter.next();
			AsyncContext asyncContext = entry.getValue();
			if (asyncContext.expriry < System.currentTimeMillis()) {
				iter.remove();
				try {
					Context context = new Context(_workerPool.getPoolContext());
					context.getExecutionStack().addAll(asyncContext.executionStack);
					ESBMessage message = new ESBMessage(BodyType.INVALID, null);
					message.getVariables().putAll(asyncContext.variables);
					Action action = new ThrowExceptionAction("AsyncContext expired");
					action.setNextAction(asyncContext.nextAction);
					action.process(context, message);
				} catch (Exception e) {
					Context.logger.info("Exception while expriring AsyncContext", e);
				}
			}
		}
	}

}
