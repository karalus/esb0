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
package com.artofarc.esb.context;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;

import com.artofarc.util.DataStructures;

public final class ContextPool implements AutoCloseable {

	private final LinkedBlockingDeque<Map.Entry<Context, Long>> _pool = new LinkedBlockingDeque<>();
	private final AtomicInteger poolSize = new AtomicInteger();
	private final PoolContext _poolContext;
	private volatile int _minPoolSize;
	private volatile int _maxPoolSize;
	private final long _keepAliveMillis;

	public ContextPool(PoolContext poolContext, int minPoolSize, int maxPoolSize, long keepAliveMillis) {
		_poolContext = poolContext;
		_minPoolSize = minPoolSize;
		_maxPoolSize = maxPoolSize;
		_keepAliveMillis = keepAliveMillis;
	}

	public int getPoolSize() {
		return poolSize.get();
	}

	public Date getLastAccess() {
		Map.Entry<Context, Long> context = _pool.peekFirst();
		return _pool.size() < poolSize.get() ? new Date() : context != null ? new Date(context.getValue()) : null;
	}

	public int getMinPoolSize() {
		return _minPoolSize;
	}

	public void setMinPoolSize(int minPoolSize) {
		_minPoolSize = minPoolSize;
	}

	public int getMaxPoolSize() {
		return _maxPoolSize;
	}

	public void setMaxPoolSize(int maxPoolSize) {
		_maxPoolSize = maxPoolSize;
	}

	public long getKeepAliveMillis() {
		return _keepAliveMillis;
	}

	public Context getContext() {
		Map.Entry<Context, Long> context = _pool.pollFirst();
		if (context == null) {
			int newPoolSize = poolSize.incrementAndGet();
			if (newPoolSize > _maxPoolSize) {
				poolSize.decrementAndGet();
				return null;
			} else {
				return new Context(_poolContext);
			}
		}
		return context.getKey();
	}

	public void releaseContext(Context context) {
		_pool.addFirst(DataStructures.createEntry(context, System.currentTimeMillis()));
	}

	public void shrinkPool() {
		int overflow = poolSize.get() - _minPoolSize;
		while (overflow > 0) {
			Map.Entry<Context, Long> candidate = _pool.peekLast();
			if (candidate == null || System.currentTimeMillis() - candidate.getValue() < _keepAliveMillis) break;
			Map.Entry<Context, Long> context = _pool.pollLast();
			if (context != candidate) {
				if (context != null) _pool.addLast(context);
				break;
			}
			overflow = poolSize.decrementAndGet() - _minPoolSize;
			context.getKey().close();
		}
	}

	@Override
	public void close() throws InterruptedException {
		while (poolSize.getAndDecrement() > 0) {
			// this possibly blocks for a long time
			Map.Entry<Context, Long> context = _pool.take();
			context.getKey().close();
		}
		poolSize.set(0);
	}

}
