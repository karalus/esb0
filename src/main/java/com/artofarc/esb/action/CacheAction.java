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
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.resource.LRUCacheWithExpirationFactory;

public class CacheAction extends Action {

	private final String _keyExp, _valueName;
	private final Action _cacheAction;
	private final boolean _notWriteOnly;
	private final LRUCacheWithExpirationFactory.Cache _cache;
	private final long _ttl;

	public CacheAction(GlobalContext globalContext, String keyExp, String valueName, Action cacheAction, boolean writeOnly, String cacheName, int maxSize, long ttl) throws Exception {
		_keyExp = keyExp;
		_valueName = valueName;
		_cacheAction = cacheAction;
		_notWriteOnly = !writeOnly;
		LRUCacheWithExpirationFactory factory = globalContext.getResourceFactory(LRUCacheWithExpirationFactory.class);
		_cache = factory.getResource(cacheName, maxSize);
		_ttl = ttl;
	}

	private boolean isValueBody() {
		return "body".equals(_valueName);
	}

	@Override
	protected boolean isPipelineStop() {
		return isValueBody() || _nextAction == null || _nextAction.isPipelineStop() || _cacheAction.isPipelineStop();
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		final Object key = bindVariable(_keyExp, context, message);
		if (key != null) {
			checkAtomic(key, _keyExp);
			if (_nextAction != null) {
				context.getExecutionStack().push(_nextAction);
			}
			Action action = null;
			if (_notWriteOnly && _cache.containsKey(key)) {
				if (!isValueBody()) {
					message.putVariable(_valueName, _cache.get(key));
				}
			} else {
				action = _cacheAction;
				context.getExecutionStack().push(new TerminalAction() {

					@Override
					protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
						super.execute(context, execContext, message, nextActionIsPipelineStop);
						Object value = isValueBody() ? message.getBodyAsString(context) : resolve(message, _valueName, true);
						_cache.put(key, value, _ttl);
					}
				});
			}
			return new ExecutionContext(key, action);
		} else {
			throw new ExecutionException(this, _keyExp + " is not set");
		}
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		if (_notWriteOnly && isValueBody()) {
			Object key = execContext.getResource();
			if (_cache.containsKey(key)) {
				message.getHeaders().clear();
				message.reset(null, _cache.get(key));
			}
		}
	}

	@Override
	protected Action nextAction(ExecutionContext execContext) {
		return execContext.getResource2();
	}

}
