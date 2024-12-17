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
package com.artofarc.util;

import java.util.Map;

public class ObjectPool<T> {

	private final Map<T, T> _pool;

	@SuppressWarnings("unchecked")
	public ObjectPool(Map<?, ?> pool) {
		_pool = (Map<T, T>) pool;
	}

	public final synchronized T intern(T instance) {
		T existing = _pool.putIfAbsent(instance, instance);
		return existing != null ? existing : instance;
	}

}
