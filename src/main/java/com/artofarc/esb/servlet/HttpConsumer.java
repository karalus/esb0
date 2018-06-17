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
package com.artofarc.esb.servlet;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.PoolContext;

public class HttpConsumer extends ConsumerPort implements AutoCloseable {

   private final BlockingQueue<Context> pool;
   private final AtomicInteger poolSize = new AtomicInteger();
   private final int minPoolSize;
   private final int maxPoolSize;
   private final long keepAliveMillis;
   
   private volatile long lastAccess;
   
	public HttpConsumer(String uri, int minPool, int maxPool, long keepAlive) {
		super(uri);
		minPoolSize = minPool;
		maxPoolSize = maxPool;
		keepAliveMillis = keepAlive;
      pool = new LinkedBlockingQueue<>(maxPool);
	}
	
	public HttpConsumer(String uri) {
		this(uri, 1, 100, 300000L);
	}
	
	public int getPoolSize() {
		return poolSize.get();
	}

	@Override
   public void enable(boolean enable) throws Exception {
      super.enable(enable);
      if (!enable) {
      	close();
      }
   }

   public Context getContext(PoolContext poolContext) throws Exception {
   	Context context = pool.poll();
   	if (context == null) {
   		int newPoolSize = poolSize.incrementAndGet();
   		if (newPoolSize > maxPoolSize) {
   			poolSize.decrementAndGet();
   		} else {
   			context = new Context(poolContext);
   		}
   	}
   	return context;
   }
   
   public void releaseContext(Context context) {
   	lastAccess = System.currentTimeMillis();
   	pool.add(context);
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
   					logger.info("Context not given back to pool, yet");
   					break;
   				}
   			}
   		}
   	}
   }

	@Override
	public void close() throws Exception {
		while (poolSize.getAndDecrement() > 0) {
			Context context = pool.poll(30L, TimeUnit.SECONDS);
			if (context == null) throw new TimeoutException("Timeout while closing context");
			context.close();
		}
		poolSize.set(0);
	}
 
}
