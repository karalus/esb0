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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

import com.artofarc.esb.jms.JMSConnectionData;
import com.artofarc.esb.jms.JMSConnectionProvider;
import com.artofarc.util.XQuerySource;

public final class WorkerPool implements AutoCloseable, Runnable, com.artofarc.esb.mbean.WorkerPoolMXBean {

	private final PoolContext _poolContext;
	private final ContextPool _contextPool;
	private final WorkerPoolThreadFactory _threadFactory;
	private final ThreadPoolExecutor _executorService;
	private final ScheduledExecutorService _scheduledExecutorService;
	private final AsyncProcessingPool _asyncProcessingPool;
	private final ConcurrentHashMap<XQuerySource, Integer> _cachedXQueries = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Thread, String> _threads = new ConcurrentHashMap<>();

	public WorkerPool(PoolContext poolContext, String name, int minThreads, int maxThreads, int priority, int queueDepth, int scheduledThreads, boolean allowCoreThreadTimeOut) {
		_contextPool = new ContextPool(_poolContext = poolContext, minThreads + scheduledThreads, maxThreads + scheduledThreads, 60000L, true);
		if (maxThreads > 0 || scheduledThreads > 0) {
			_threadFactory = new WorkerPoolThreadFactory(name, priority);
		} else {
			_threadFactory = null;
		}
		if (maxThreads > 0) {
			_executorService = new ThreadPoolExecutor(minThreads, maxThreads, 60L, TimeUnit.SECONDS, queueDepth > 0 ? new ArrayBlockingQueue<>(queueDepth) : new LinkedBlockingQueue<>(), _threadFactory);
			_executorService.allowCoreThreadTimeOut(allowCoreThreadTimeOut);
		} else {
			_executorService = null;
		}
		if (scheduledThreads > 0) {
			_scheduledExecutorService = Executors.newScheduledThreadPool(scheduledThreads, _threadFactory);
			_scheduledExecutorService.scheduleAtFixedRate(this, 60L, 60L, TimeUnit.SECONDS);
			_asyncProcessingPool = new AsyncProcessingPool(this);
		} else {
			_scheduledExecutorService = null;
			_asyncProcessingPool = null;
		}
	}

	public WorkerPool(GlobalContext globalContext, String name, int minThreads, int maxThreads, int priority, int queueDepth, int scheduledThreads, boolean allowCoreThreadTimeOut) {
		this(new PoolContext(globalContext, name), name, minThreads, maxThreads, priority, queueDepth, scheduledThreads, allowCoreThreadTimeOut);
	}

	WorkerPool(GlobalContext globalContext, String name, int nThreads) {
		this(new PoolContext(globalContext, name), name, nThreads, nThreads, Thread.NORM_PRIORITY, 0, 2, true);
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

	public Context getContext() throws InterruptedException {
		return _contextPool.getContext();
	}

	public void releaseContext(Context context) {
		_contextPool.releaseContext(context);
	}

	public void addThread(Thread thread, String info) {
		if (_scheduledExecutorService != null && thread.getThreadGroup() != _threadFactory && _threads.put(thread, info) == null) {
			PoolContext.logger.debug("adding thread " + thread.getName() + " for " + info);
		}
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
		try {
			_contextPool.close();
		} catch (InterruptedException e) {
			PoolContext.logger.error("Unexpected", e);
		}
		if (_threadFactory != null) {
			while (_threadFactory.activeCount() > 0) {
				PoolContext.logger.info("WorkerPoolThreadFactory not terminated, yet: ", _threadFactory.getName());
				Thread.yield();
			}
			_threadFactory.destroy();
		}
	}

	// Methods for monitoring

	public String getName() {
		return _threadFactory != null ? _threadFactory.getName() : null;
	}

	public int getMaximumPoolSize() {
		return _executorService != null ? _executorService.getMaximumPoolSize() : -1;
	}

	public void setMaximumPoolSize(int maximumPoolSize) {
		if (_executorService != null) {
			_executorService.setMaximumPoolSize(maximumPoolSize);
		}
	}

	public int getCorePoolSize() {
		return _executorService != null ? _executorService.getCorePoolSize() : -1;
	}

	public void setCorePoolSize(int corePoolSize) {
		if (_executorService != null) {
			_executorService.setCorePoolSize(corePoolSize);
		}
	}

	public int getPoolSize() {
		return _executorService != null ? _executorService.getPoolSize() : -1;
	}

	public int getActiveCount() {
		return _executorService != null ? _executorService.getActiveCount() + _threads.size() : _threads.size();
	}

	public int getLargestPoolSize() {
		return _executorService != null ? _executorService.getLargestPoolSize() : -1;
	}

	public long getCompletedTaskCount() {
		return _executorService != null ? _executorService.getCompletedTaskCount() : -1;
	}

	public int getQueueSize() {
		return _executorService != null ? _executorService.getQueue().size() : -1;
	}

	public int getRemainingCapacity() {
		return _executorService != null ? _executorService.getQueue().remainingCapacity() : -1;
	}

	public int getAsyncProcessingPoolSize() {
		return _asyncProcessingPool != null ? _asyncProcessingPool.getPoolSize() : -1;
	}

	public Set<String> getJMSSessionFactories() {
		JMSConnectionProvider jmsConnectionProvider = _poolContext.getResourceFactory(JMSConnectionProvider.class);
		Set<String> result = new HashSet<>();
		for (JMSConnectionData jmsConnectionData : jmsConnectionProvider.getResourceDescriptors()) {
			result.add(jmsConnectionData.toString());
		}
		return result;
	}

	public List<String> getCachedXQueries() {
		List<String> result = new ArrayList<>(); 
		for (XQuerySource xquery : _cachedXQueries.keySet()) {
			result.add(xquery.toString());
		}
		return result;
	}

	public void addCachedXQuery(XQuerySource xquery) {
		Integer count;
		if ((count = _cachedXQueries.putIfAbsent(xquery, 1)) != null) {
			while (!_cachedXQueries.replace(xquery, count, ++count)) {
				count = _cachedXQueries.get(xquery);
			}
		}
	}

	public void removeCachedXQuery(XQuerySource xquery) {
		Integer count;
		do {
			count = _cachedXQueries.get(xquery);
		} while (count != null && !(count == 1 ? _cachedXQueries.remove(xquery, count) : _cachedXQueries.replace(xquery, count, --count)));
	}

	public int getCachedXQueriesTotal() {
		int sum = 0;
		for (Integer count : _cachedXQueries.values()) {
			sum += count;
		}
		return sum;
	}

	public List<String> getActiveThreads() {
		List<String> result = new ArrayList<>();
		if (_threadFactory != null) {
			Thread[] list = new Thread[_threadFactory.activeCount()];
			int c = _threadFactory.enumerate(list);
			for (int i = 0; i < c; ++i) {
				result.add(list[i].getName());
			}
		}
		for (Thread thread : _threads.keySet()) {
			if (thread.isAlive()) {
				result.add(thread.getName());
			}
		}
		return result;
	}

	@Override
	public void run() {
		for (Map.Entry<Thread, String> entry : _threads.entrySet()) {
			Thread thread = entry.getKey();
			if (!thread.isAlive()) {
				_threads.remove(thread);
				PoolContext.logger.debug("removing Thread " + thread.getName() + " for " + entry.getValue());
			}
		}
	}

	public int getContextCount() {
		return _contextPool.getPoolSize();
	}

}
