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
package com.artofarc.esb;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.WorkerPool;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;

public final class TimerService extends ConsumerPort implements AutoCloseable, Runnable, com.artofarc.esb.mbean.TimerServiceMXBean {

	private final String _workerPoolName;
	private final int _initialDelay, _period;
	private final boolean _fixedDelay;

	private WorkerPool _workerPool;

	private volatile ScheduledFuture<?> _future;

	public TimerService(String uri, String workerPool, int initialDelay, int period, boolean fixedDelay) {
		super(uri);
		_workerPoolName = workerPool;
		_initialDelay = initialDelay;
		_period = period;
		_fixedDelay = fixedDelay;
	}

	@Override
	public void init(GlobalContext globalContext) {
		_workerPool = globalContext.getWorkerPool(_workerPoolName);
		if (super.isEnabled()) {
			enable(true);
		}
	}

	@Override
	public boolean isEnabled() {
		return _future != null;
	}

	@Override
	public void enable(boolean enable) {
		if (enable) {
			if (_future == null) {
				if (_fixedDelay) {
					_future = _workerPool.getScheduledExecutorService().scheduleWithFixedDelay(this, _initialDelay, _period, TimeUnit.SECONDS);
				} else {
					_future = _workerPool.getScheduledExecutorService().scheduleAtFixedRate(this, _initialDelay, _period, TimeUnit.SECONDS);
				}
			}
		} else {
			close();
		}
	}

	public Long getDelay() {
		return _future != null ? _future.getDelay(TimeUnit.SECONDS) : null;
	}

	@Override
	public void close() {
		if (_future != null) {
			_future.cancel(false);
			_future = null;
		}
	}

	@Override
	public void run() {
		try {
			Context context = _workerPool.getContext();
			try {
				process(context, new ESBMessage(BodyType.INVALID, null));
			} catch (Exception e) {
				logger.error("Exception in forked action pipeline", e);
			} finally {
				_workerPool.releaseContext(context);
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
