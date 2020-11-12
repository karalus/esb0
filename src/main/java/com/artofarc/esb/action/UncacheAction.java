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
		final Object key = bindVariable(_keyExp, context, message);
		if (key != null) {
			checkAtomic(key, _keyExp);
			LRUCacheWithExpirationFactory<Object, Object[]>.Cache cache = _factory.getResource(_cacheName);
			cache.remove(key);
			return null;
		} else {
			throw new ExecutionException(this, _keyExp + " is not set");
		}
	}

}
