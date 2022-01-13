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

import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;

import com.artofarc.util.Collections;

public final class ContextPool implements AutoCloseable {

	private final LinkedBlockingDeque<Map.Entry<Context, Long>> pool;
	private final AtomicInteger poolSize = new AtomicInteger();
	private final int minPoolSize;
	private final int maxPoolSize;
	private final long keepAliveMillis;
	private final boolean isBlocking;
	private final PoolContext _poolContext;

	public ContextPool(PoolContext poolContext, int minPool, int maxPool, long keepAlive, boolean blocking) {
		_poolContext = poolContext;
		minPoolSize = minPool;
		maxPoolSize = maxPool;
		keepAliveMillis = keepAlive;
		isBlocking = blocking;
		pool = new LinkedBlockingDeque<>(maxPool);
	}

	public int getPoolSize() {
		return poolSize.get();
	}

	public Long getLastAccess() {
		Map.Entry<Context, Long> context = pool.peekFirst();
		return pool.size() < poolSize.get() ? System.currentTimeMillis() : context != null ? context.getValue() : null;
	}

	public int getMinPoolSize() {
		return minPoolSize;
	}

	public int getMaxPoolSize() {
		return maxPoolSize;
	}

	public long getKeepAliveMillis() {
		return keepAliveMillis;
	}

	public Context getContext() throws InterruptedException {
		Map.Entry<Context, Long> context = pool.pollFirst();
		if (context == null) {
			int newPoolSize = poolSize.incrementAndGet();
			if (newPoolSize > maxPoolSize) {
				poolSize.decrementAndGet();
				if (isBlocking) {
					context = pool.takeFirst();
				} else {
					return null;
				}
			} else {
				return new Context(_poolContext);
			}
		}
		return context.getKey();
	}

	public void releaseContext(Context context) {
		pool.addFirst(Collections.createEntry(context, System.currentTimeMillis()));
	}

	public void shrinkPool() {
		int overflow = poolSize.get() - minPoolSize;
		while (overflow > 0) {
			Map.Entry<Context, Long> candidate = pool.peekLast();
			if (candidate == null || System.currentTimeMillis() - candidate.getValue() < keepAliveMillis) break;
			Map.Entry<Context, Long> context = pool.pollLast();
			if (context != candidate) {
				if (context != null) pool.addLast(context);
				break;
			}
			overflow = poolSize.decrementAndGet() - minPoolSize;
			context.getKey().close();
		}
	}

	@Override
	public void close() throws InterruptedException {
		while (poolSize.getAndDecrement() > 0) {
			// this possibly blocks for a long time
			Map.Entry<Context, Long> context = pool.take();
			context.getKey().close();
		}
		poolSize.set(0);
	}

}
