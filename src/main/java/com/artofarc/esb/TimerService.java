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

import java.util.concurrent.ScheduledFuture;

import javax.xml.datatype.XMLGregorianCalendar;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;

public final class TimerService extends SchedulingConsumerPort implements Runnable, com.artofarc.esb.mbean.TimerServiceMXBean {

	private final long _initialDelay;

	private volatile ScheduledFuture<?> _future;

	public TimerService(String uri, String workerPool, XMLGregorianCalendar at, String timeUnit, int period, int initialDelay, boolean fixedDelay) {
		super(uri, workerPool, at, timeUnit, period, fixedDelay);
		_initialDelay = initialDelay;
	}

	public void init(GlobalContext globalContext) {
		initWorkerPool(globalContext);
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
				_future = schedule(this, _initialDelay);
			}
		} else {
			close();
		}
	}

	public final Long getDelay() {
		return _future != null ? _future.getDelay(getTimeUnit()) : null;
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
		Context context = _workerPool.getContext();
		try {
			process(context, new ESBMessage(BodyType.INVALID, null));
		} catch (Exception e) {
			logger.error("Exception in forked action pipeline", e);
		} finally {
			_workerPool.releaseContext(context);
			if (needsReschedule()) {
				_future = schedule(this, _initialDelay);
			}
		}
	}

	@Override
	public void bind(Registry registry) {
		registry.registerMBean(this, getMBeanPostfix());
	}

	@Override
	public void unbind(Registry registry) {
		registry.unbindTimerService(this);
	}

}
