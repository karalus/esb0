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

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class ContextPool implements AutoCloseable {

	private final LinkedBlockingQueue<Context> pool;
	private final AtomicInteger poolSize = new AtomicInteger();
	private final int minPoolSize;
	private final int maxPoolSize;
	private final long keepAliveMillis;
	private final boolean isBlocking;
	private final PoolContext _poolContext;

	private volatile long lastAccess;

	public ContextPool(PoolContext poolContext, int minPool, int maxPool, long keepAlive, boolean blocking) {
		_poolContext = poolContext;
		minPoolSize = minPool;
		maxPoolSize = maxPool;
		keepAliveMillis = keepAlive;
		isBlocking = blocking;
		pool = new LinkedBlockingQueue<>(maxPool);
	}

	public int getPoolSize() {
		return poolSize.get();
	}

	public long getLastAccess() {
		return lastAccess;
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

	public Context getContext() throws Exception {
		Context context = pool.poll();
		if (context == null) {
			int newPoolSize = poolSize.incrementAndGet();
			if (newPoolSize > maxPoolSize) {
				poolSize.decrementAndGet();
				if (isBlocking) {
					context = pool.take();
				}
			} else {
				try {
					context = new Context(_poolContext);
				} catch (Exception e) {
					poolSize.decrementAndGet();
					throw e;
				}
			}
		}
		return context;
	}

	public void releaseContext(Context context) {
		pool.add(context);
		lastAccess = System.currentTimeMillis();
	}

	public void shrinkPool() {
		int overflow = poolSize.get() - minPoolSize;
		if (overflow > 0) {
			long timediff = System.currentTimeMillis() - lastAccess;
			if (timediff > keepAliveMillis) {
				while (overflow > 0) {
					Context context = pool.poll();
					if (context != null) {
						overflow = poolSize.decrementAndGet() - minPoolSize;
						context.close();
					} else {
						Context.logger.info("Context not given back to pool, yet");
						break;
					}
				}
			}
		}
	}

	@Override
	public void close() throws InterruptedException {
		while (poolSize.getAndDecrement() > 0) {
			// this possibly blocks for a long time
			Context context = pool.take();
			context.close();
		}
		poolSize.set(0);
	}

}
