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
			reset();
		} catch (Exception e) {
			throw ReflectionUtils.convert(e, RuntimeException.class);
		}
	}

	abstract protected R createResource(D descriptor, P param) throws E;

	protected void init(Map<D, R> pool) throws Exception {
	}

	public final void reset() throws Exception {
		Map<D, R> pool = new HashMap<>();
		init(pool);
		_pool = pool;
	}

	public final R getResource(D descriptor, P param) throws E {
		if (_pool.containsKey(descriptor)) {
			return _pool.get(descriptor);
		}
		_lock.lock();
		try {
			// Really! Things maybe changed while acquiring the lock.
			if (_pool.containsKey(descriptor)) {
				return _pool.get(descriptor);
			}
			R resource = createResource(descriptor, param);
			Map<D, R> pool = new HashMap<>(_pool);
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

	public final R removeResource(D descriptor) {
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

	protected final Collection<R> getResources() {
		return _pool.values();
	}

}
