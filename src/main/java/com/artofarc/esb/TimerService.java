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

import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.XMLGregorianCalendar;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.WorkerPool;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;

public final class TimerService extends ConsumerPort implements Runnable, com.artofarc.esb.mbean.TimerServiceMXBean {

	private final String _workerPoolName;
	private final TimeUnit _timeUnit;
	private final long _initialDelay, _period;
	private final XMLGregorianCalendar _at;
	private final boolean _fixedDelay;

	private WorkerPool _workerPool;

	private volatile ScheduledFuture<?> _future;

	public TimerService(String uri, String workerPool, XMLGregorianCalendar at, String timeUnit, int period, int initialDelay, boolean fixedDelay) {
		super(uri);
		_workerPoolName = workerPool;
		_at = at;
		_timeUnit = TimeUnit.valueOf(timeUnit.toUpperCase());
		_period = period;
		_initialDelay = initialDelay;
		_fixedDelay = fixedDelay;
	}

	public TimeUnit getTimeUnit() {
		return _timeUnit;
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
				if (_at != null) {
					_future = _workerPool.getScheduledExecutorService().schedule(this, getNextDelay(), TimeUnit.MILLISECONDS);
				} else {
					if (_fixedDelay) {
						_future = _workerPool.getScheduledExecutorService().scheduleWithFixedDelay(this, _initialDelay, _period, _timeUnit);
					} else {
						_future = _workerPool.getScheduledExecutorService().scheduleAtFixedRate(this, _initialDelay, _period, _timeUnit);
					}
				}
			}
		} else {
			close();
		}
	}

	private long getNextDelay() {
		return millisUntilNext(_at.getHour(), _at.getMinute(), _at.getSecond(), _at.getTimeZone(DatatypeConstants.FIELD_UNDEFINED)) % _timeUnit.toMillis(_period);
	}

	public static long millisUntilNext(int hour, int minute, int second, TimeZone timeZone) {
		Calendar now = Calendar.getInstance(timeZone);
		Calendar atTime = (Calendar) now.clone();
		atTime.set(Calendar.HOUR_OF_DAY, hour);
		atTime.set(Calendar.MINUTE, minute);
		atTime.set(Calendar.SECOND, second);
		atTime.set(Calendar.MILLISECOND, 0);
		if (now.after(atTime)) {
			atTime.add(Calendar.DATE, 1);
		}
		return atTime.getTimeInMillis() - now.getTimeInMillis();
	}

	public final Long getDelay() {
		return _future != null ? _future.getDelay(_timeUnit) : null;
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
				if (_at != null) {
					_future = _workerPool.getScheduledExecutorService().schedule(this, getNextDelay(), TimeUnit.MILLISECONDS);
				}
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
