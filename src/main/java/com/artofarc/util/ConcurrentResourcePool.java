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
package com.artofarc.util;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

public abstract class ConcurrentResourcePool<R, D, P, E extends Exception> {

	private final ReentrantLock _lock = new ReentrantLock();
	private volatile Map<D, R> _pool;

	protected ConcurrentResourcePool() {
		try {
			reset(true);
		} catch (Exception e) {
			throw ReflectionUtils.convert(e, RuntimeException.class);
		}
	}

	abstract protected R createResource(D descriptor, P param) throws E;

	protected void init(Map<D, R> pool) throws Exception {
	}

	public final void reset(boolean clear) throws Exception {
		Map<D, R> pool = clear ? new HashMap<>() : new HashMap<>(_pool);
		init(pool);
		_pool = pool;
	}

	public final R peekResource(D descriptor) {
		return _pool.get(descriptor);
	}

	public final R getResource(D descriptor, P param) throws E {
		Map<D, R> pool = _pool;
		if (pool.containsKey(descriptor)) {
			return pool.get(descriptor);
		}
		_lock.lock();
		try {
			// Really! Things maybe changed while acquiring the lock.
			pool = _pool;
			if (pool.containsKey(descriptor)) {
				return pool.get(descriptor);
			}
			R resource = createResource(descriptor, param);
			pool = new HashMap<>(pool);
			pool.put(descriptor, resource);
			_pool = pool;
			return resource;
		} finally {
			_lock.unlock();
		}
	}

	public final R getResource(D descriptor) throws E {
		return getResource(descriptor, null);
	}

	public final R putResource(D descriptor, R resource) {
		_lock.lock();
		try {
			Map<D, R> pool = new HashMap<>(_pool);
			R old = pool.put(descriptor, resource);
			if (old != resource) {
				_pool = pool;
			}
			return old;
		} finally {
			_lock.unlock();
		}
	}

	public final R removeResource(D descriptor) {
		if (!_pool.containsKey(descriptor)) {
			return null;
		}
		_lock.lock();
		try {
			Map<D, R> pool = new HashMap<>(_pool);
			R resource = pool.remove(descriptor);
			_pool = pool;
			return resource;
		} finally {
			_lock.unlock();
		}
	}

	public final Set<D> getResourceDescriptors() {
		return _pool.keySet();
	}

	public final Collection<R> getResources() {
		return _pool.values();
	}

}
