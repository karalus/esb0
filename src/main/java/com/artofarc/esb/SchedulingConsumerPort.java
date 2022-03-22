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

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.XMLGregorianCalendar;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.WorkerPool;

public abstract class SchedulingConsumerPort extends ConsumerPort {

	protected final String _workerPoolName;
	protected WorkerPool _workerPool;
	protected final long _period;
	private final TimeUnit _timeUnit;
	private final XMLGregorianCalendar _at;
	private final boolean _fixedDelay;

	public SchedulingConsumerPort(String uri, String workerPool, XMLGregorianCalendar at, String timeUnit, long period, boolean fixedDelay) {
		super(uri);
		_workerPoolName = workerPool;
		_at = at;
		_timeUnit = TimeUnit.valueOf(timeUnit.toUpperCase(Locale.ROOT));
		_period = at != null ? _timeUnit.toMillis(period) : period;
		_fixedDelay = fixedDelay;
	}

	public final TimeUnit getTimeUnit() {
		return _timeUnit;
	}

	protected final void initWorkerPool(GlobalContext globalContext) {
		_workerPool = globalContext.getWorkerPool(_workerPoolName);
	}

	protected final ScheduledFuture<?> schedule(Runnable runnable, long initialDelay) {
		if (_at != null) {
			initialDelay = millisUntilNext(_at.getHour(), _at.getMinute(), _at.getSecond(), _at.getTimeZone(DatatypeConstants.FIELD_UNDEFINED)) % _period;
			return _workerPool.getScheduledExecutorService().schedule(runnable, initialDelay, TimeUnit.MILLISECONDS);
		}
		if (_fixedDelay) {
			return _workerPool.getScheduledExecutorService().scheduleWithFixedDelay(runnable, initialDelay, _period, _timeUnit);
		} else {
			return _workerPool.getScheduledExecutorService().scheduleAtFixedRate(runnable, initialDelay, _period, _timeUnit);
		}
	}

	protected final boolean needsReschedule() {
		return _at != null;
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

}
