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
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

public final class WorkerPool implements AutoCloseable, Runnable, RejectedExecutionHandler, com.artofarc.esb.mbean.WorkerPoolMXBean {

	private final String _name;
	private final int _queueDepth, _scheduledThreads;
	private final boolean _allowCoreThreadTimeOut;
	private volatile boolean _retry;
	private final PoolContext _poolContext;
	private final ContextPool _contextPool;
	private final WorkerPoolThreadFactory _threadFactory;
	private final ThreadPoolExecutor _executorService;
	private final ScheduledExecutorService _scheduledExecutorService;
	private final AsyncProcessingPool _asyncProcessingPool;
	private final Map<Thread, String> _threads = new ConcurrentHashMap<>();

	public WorkerPool(PoolContext poolContext, String name, int minThreads, int maxThreads, int priority, int queueDepth, int scheduledThreads, boolean allowCoreThreadTimeOut, boolean retry) {
		_name = name;
		_queueDepth = queueDepth;
		_scheduledThreads = scheduledThreads;
		_allowCoreThreadTimeOut = allowCoreThreadTimeOut;
		_retry = retry;
		_contextPool = new ContextPool(_poolContext = poolContext, allowCoreThreadTimeOut ? scheduledThreads : minThreads + scheduledThreads, maxThreads + scheduledThreads, 60000L);
		if (maxThreads > 0 || scheduledThreads > 0) {
			_threadFactory = new WorkerPoolThreadFactory(name, priority);
		} else {
			_threadFactory = null;
		}
		if (maxThreads > 0) {
			BlockingQueue<Runnable> workQueue;
			if (queueDepth < 0) {
				workQueue = new LinkedBlockingQueue<>();
			} else if (queueDepth == 0) {
				// https://stackoverflow.com/questions/10186397/threadpoolexecutor-without-a-queue
				workQueue = new SynchronousQueue<>();
			} else {
				workQueue = new ArrayBlockingQueue<>(queueDepth);
			}
			_executorService = new ThreadPoolExecutor(minThreads, maxThreads, 60L, TimeUnit.SECONDS, workQueue, _threadFactory, this);
			_executorService.allowCoreThreadTimeOut(allowCoreThreadTimeOut);
			_asyncProcessingPool = new AsyncProcessingPool(this);
		} else {
			_executorService = null;
			_asyncProcessingPool = null;
		}
		if (scheduledThreads > 0) {
			_scheduledExecutorService = Executors.newScheduledThreadPool(scheduledThreads, _threadFactory);
			_scheduledExecutorService.scheduleAtFixedRate(this, 60L, 60L, TimeUnit.SECONDS);
		} else {
			_scheduledExecutorService = null;
		}
	}

	public WorkerPool(GlobalContext globalContext, String name, int minThreads, int maxThreads, int priority, int queueDepth, int scheduledThreads, boolean allowCoreThreadTimeOut, boolean retry) {
		this(new PoolContext(globalContext, name), name, minThreads, maxThreads, priority, queueDepth, scheduledThreads, allowCoreThreadTimeOut, retry);
	}

	WorkerPool(GlobalContext globalContext, String name, int nThreads) {
		this(new PoolContext(globalContext, name), name, nThreads, nThreads, Thread.NORM_PRIORITY, 0, 2, true, true);
	}

	public boolean tryUpdate(int minThreads, int maxThreads, int priority, int queueDepth, int scheduledThreads, boolean allowCoreThreadTimeOut, boolean retry) {
		if (maxThreads > 0 && getMaximumPoolSize() < 0 || queueDepth != _queueDepth || scheduledThreads != _scheduledThreads || allowCoreThreadTimeOut != _allowCoreThreadTimeOut) {
			return false;
		}
		if (minThreads > getMaximumPoolSize()) {
			setMaximumPoolSize(maxThreads);
			setCorePoolSize(minThreads);
		} else {
			setCorePoolSize(minThreads);
			setMaximumPoolSize(maxThreads);
		}
		_retry = retry;
		return true;
	}

	public String getName() {
		return _name;
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

	public Context getContext() {
		Context context = _contextPool.getContext();
		if (context == null) {
			throw new RejectedExecutionException("ContextPool size wrong or resource leak");
		}
		return context;
	}

	public void releaseContext(Context context) {
		_contextPool.releaseContext(context);
	}

	public Future<?> executeLongLived(Runnable task, String name) {
		FutureTask<?> futureTask = new FutureTask<>(task, null);
		Thread thread = new Thread(futureTask, "LongLived-" + name);
		thread.start();
		return futureTask;//_executorService.submit(task);
	}

	public void addThread(Thread thread, String info) {
		if (_scheduledExecutorService != null && _threads.putIfAbsent(thread, info) == null) {
			PoolContext.logger.debug("adding thread {} for {}", thread.getName(), info);
		}
	}

	@Override
	public void run() {
		for (Map.Entry<Thread, String> entry : _threads.entrySet()) {
			Thread thread = entry.getKey();
			if (!thread.isAlive()) {
				_threads.remove(thread);
				PoolContext.logger.debug("removing Thread {} for {}", thread.getName(), entry.getValue());
			}
		}
	}

	@Override
	public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
		if (_retry) {
			try {
				for (;;) {
					if (executor.isShutdown()) {
						throw new RejectedExecutionException("Already closed " + _name);
					}
					if (executor.getQueue().offer(r, 1, TimeUnit.SECONDS)) {
						break;
					}
					PoolContext.logger.warn("Could not submit to worker pool {}", _name);
				}
			} catch (InterruptedException e) {
				throw new RejectedExecutionException("Interrupted while trying to submit to " + _name);
			}
		} else {
			throw new RejectedExecutionException("Task rejected from " + _name);
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
	}

	public int getMaximumPoolSize() {
		return _executorService != null ? _executorService.getMaximumPoolSize() : -1;
	}

	public void setMaximumPoolSize(int maximumPoolSize) {
		_contextPool.setMaxPoolSize(maximumPoolSize + _scheduledThreads);
		if (_executorService != null) {
			_executorService.setMaximumPoolSize(maximumPoolSize);
		}
	}

	public int getCorePoolSize() {
		return _executorService != null ? _executorService.getCorePoolSize() : -1;
	}

	public void setCorePoolSize(int corePoolSize) {
		_contextPool.setMinPoolSize(_allowCoreThreadTimeOut ? _scheduledThreads : corePoolSize + _scheduledThreads);
		if (_executorService != null) {
			_executorService.setCorePoolSize(corePoolSize);
		}
	}

	public int getGuaranteedPoolSize() {
		return _queueDepth == 0 ? getMaximumPoolSize() : _allowCoreThreadTimeOut ? 0 : getCorePoolSize();
	}

	// Methods for monitoring

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

	public int getScheduledThreadPoolSize() {
		return _scheduledThreads;
	}

	public int getAsyncProcessingPoolSize() {
		return _asyncProcessingPool != null ? _asyncProcessingPool.getPoolSize() : -1;
	}

	public Set<String> getJMSSessionFactories() {
		return _poolContext.getJMSSessionFactories();
	}

	public List<String> getCachedXQueries() {
		return _poolContext.getCachedXQueries();
	}

	public int getCachedXQueriesTotal() {
		return _poolContext.getCachedXQueriesTotal();
	}

	public List<String> getActiveThreads() {
		List<String> result = _threadFactory != null ? _threadFactory.getActiveThreads() : new ArrayList<>();
		for (Thread thread : _threads.keySet()) {
			if (thread.isAlive()) {
				result.add(thread.getName());
			}
		}
		return result;
	}

	public int getContextPoolSize() {
		return _contextPool.getPoolSize();
	}

	public Date getContextPoolLastAccess() {
		return _contextPool.getLastAccess();
	}

}
