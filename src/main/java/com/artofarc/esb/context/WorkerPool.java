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

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class WorkerPool implements AutoCloseable, com.artofarc.esb.mbean.WorkerPoolMXBean {

	private final PoolContext _poolContext;
	private final WorkerPoolThreadFactory _threadFactory;
	private final ThreadPoolExecutor _executorService;
	private final ScheduledExecutorService _scheduledExecutorService;
	private final AsyncProcessingPool _asyncProcessingPool;

	public WorkerPool(GlobalContext globalContext, String name, int minThreads, int maxThreads, int priority, int queueDepth, int scheduledThreads) {
		_poolContext = new PoolContext(globalContext, name);
		_threadFactory = new WorkerPoolThreadFactory(name, _poolContext, priority);
		if (maxThreads > 0 && queueDepth > 0) {
			_executorService = new ThreadPoolExecutor(minThreads, maxThreads, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(queueDepth), _threadFactory);
		} else {
			_executorService = null;
		}
		if (scheduledThreads > 0) {
			_scheduledExecutorService = Executors.newScheduledThreadPool(scheduledThreads, _threadFactory);
			_asyncProcessingPool = new AsyncProcessingPool(this);
		} else {
			_scheduledExecutorService = null;
			_asyncProcessingPool = null;
		}
	}

	WorkerPool(GlobalContext globalContext, int nThreads) {
		_poolContext = new PoolContext(globalContext, null);
		_threadFactory = new WorkerPoolThreadFactory("default", _poolContext, Thread.NORM_PRIORITY);
		// Refer to Executors.newFixedThreadPool 
		_executorService = new ThreadPoolExecutor(nThreads, nThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), _threadFactory);
		_scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(_threadFactory);
		_asyncProcessingPool = new AsyncProcessingPool(this);
	}
	
	public PoolContext getPoolContext() {
		return _poolContext;
	}
	
	public ExecutorService getExecutorService() {
		return _executorService;
	}

	public ScheduledExecutorService getScheduledExecutorService() {
		return _scheduledExecutorService;
	}

	public AsyncProcessingPool getAsyncProcessingPool() {
		return _asyncProcessingPool;
	}

	@Override
	public void close() {
		if (_executorService != null) {
			_executorService.shutdown();
		}
		if (_asyncProcessingPool != null) {
			_asyncProcessingPool.stop();
		}
		if (_scheduledExecutorService != null) {
			_scheduledExecutorService.shutdown();
		}
		_poolContext.close();
	}
	
	// Methods for monitoring

	public String getName() {
		return _threadFactory.getName();
	}
	
	public int getActiveCount() {
		return _threadFactory.activeCount();
	}

	public int getMaximumPoolSize() {
		return _executorService.getMaximumPoolSize();
	}

	public int getCorePoolSize() {
		return _executorService.getCorePoolSize();
	}

	public long getCompletedTaskCount() {
		return _executorService.getCompletedTaskCount();
	}

	public int getQueueSize() {
		return _executorService.getQueue().size();
	}

	public int getRemainingCapacity() {
		return _executorService.getQueue().remainingCapacity();
	}

	public int getAsyncProcessingPoolSize() {
		return _asyncProcessingPool != null ? _asyncProcessingPool.getPoolSize() : -1;
	}

}
