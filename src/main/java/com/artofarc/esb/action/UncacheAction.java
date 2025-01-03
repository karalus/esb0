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
package com.artofarc.esb.action;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.resource.LRUCacheWithExpirationFactory;

public class UncacheAction extends Action {

	private final String _keyExp, _cacheName;
	private final LRUCacheWithExpirationFactory<Object, Object[]> _factory;

	@SuppressWarnings("unchecked")
	public UncacheAction(GlobalContext globalContext, String keyExp, String cacheName) {
		_keyExp = keyExp;
		_cacheName = cacheName;
		_factory = globalContext.getResourceFactory(LRUCacheWithExpirationFactory.class);
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		LRUCacheWithExpirationFactory<Object, Object[]>.Cache cache = _factory.peekResource(_cacheName);
		if (cache != null) {
			if (_keyExp != null) {
				final Object key = eval(_keyExp, context, message);
				if (key != null) {
					checkAtomic(key, _keyExp);
					cache.remove(key);
				} else {
					throw new ExecutionException(this, _keyExp + " is not set");
				}
			} else {
				cache.clear();
			}
		}
		return null;
	}

}
