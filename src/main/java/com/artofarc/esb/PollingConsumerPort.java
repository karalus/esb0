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
package com.artofarc.esb;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.WorkerPool;

public abstract class PollingConsumerPort extends ConsumerPort implements Runnable {

	private final String _workerPoolName;
	protected WorkerPool _workerPool;

	private volatile Future<?> _future;

	public PollingConsumerPort(String uri, String workerPool) {
		super(uri);
		_workerPoolName = workerPool;
	}

	public final void init(GlobalContext globalContext) {
		_workerPool = globalContext.getWorkerPool(_workerPoolName);
		if (super.isEnabled()) {
			enable(true);
		}
	}

	protected final <T> Future<T> submit(Callable<T> callable) {
		return _workerPool.getExecutorService().submit(callable);
	}

	@Override
	public boolean isEnabled() {
		return _future != null && !_future.isDone();
	}

	@Override
	public void enable(boolean enable) {
		if (enable) {
			if (_future == null || _future.isDone()) {
				_future = _workerPool.getExecutorService().submit(this);
			}
		} else {
			close();
		}
	}

	@Override
	public void close() {
		if (_future != null) {
			_future.cancel(true);
			_future = null;
		}
	}

}
