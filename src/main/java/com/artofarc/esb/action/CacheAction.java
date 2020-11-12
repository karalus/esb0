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

import java.util.List;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.resource.LRUCacheWithExpirationFactory;

public class CacheAction extends Action {

	private final String _keyExp;
	private final List<String> _valueNames;
	private final int _indexBody;
	private final Action _cacheAction;
	private final boolean _notWriteOnly;
	private final LRUCacheWithExpirationFactory<Object, Object[]>.Cache _cache;
	private final String _ttl;

	public CacheAction(GlobalContext globalContext, String keyExp, List<String> valueNames, Action cacheAction, boolean writeOnly, String cacheName, int maxSize, String ttl) {
		_keyExp = keyExp;
		_valueNames = valueNames;
		_cacheAction = cacheAction;
		_notWriteOnly = !writeOnly;
		_indexBody = valueNames.indexOf("body");
		@SuppressWarnings("unchecked")
		LRUCacheWithExpirationFactory<Object, Object[]> factory = globalContext.getResourceFactory(LRUCacheWithExpirationFactory.class);
		_cache = factory.getResource(cacheName, maxSize);
		_ttl = ttl;
	}

	@Override
	protected boolean isPipelineStop() {
		return _indexBody >= 0 || _nextAction == null || _nextAction.isPipelineStop() || _cacheAction.isPipelineStop();
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
			Object[] values = _cache.get(key);
			if (_notWriteOnly && values != null) {
				for (int i = 0; i < _valueNames.size(); ++i) {
					if (i != _indexBody) {
						message.putVariable(_valueNames.get(i), values[i]);
					}
				}
			} else {
				action = _cacheAction;
				context.getExecutionStack().push(new TerminalAction() {

					@Override
					protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
						super.execute(context, execContext, message, nextActionIsPipelineStop);
						long ttl;
						if (Character.isDigit(_ttl.charAt(0))) {
							ttl = Long.parseLong(_ttl);
						} else {
							ttl = this.<Number> resolve(message, _ttl, true).longValue();
						}
						if (ttl > 0) {
							Object[] values = new Object[_valueNames.size()];
							for (int i = 0; i < _valueNames.size(); ++i) {
								values[i] = i != _indexBody ? resolve(message, _valueNames.get(i), true) : message.getBodyAsString(context);
							}
							_cache.put(key, values, ttl);
						}
					}
				});
			}
			return new ExecutionContext(key, action);
		} else {
			throw new ExecutionException(this, _keyExp + " is not set");
		}
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) {
		if (_notWriteOnly && _indexBody >= 0) {
			Object[] values = _cache.get(execContext.getResource());
			if (values != null) {
				message.clearHeaders();
				message.reset(null, values[_indexBody]);
			}
		}
	}

	@Override
	protected Action nextAction(ExecutionContext execContext) {
		return execContext.getResource2();
	}

}
