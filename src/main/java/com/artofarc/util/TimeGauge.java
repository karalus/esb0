/*
 * Copyright 2023 Andre Karalus
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
package com.artofarc.util;

import java.util.ArrayDeque;

import org.slf4j.Logger;

public final class TimeGauge {

	private final static Object[] EMPTY_OBJECT_ARRAY = new Object[0];

	private final Logger _logger; 
	private final boolean _debug;
	private final long _threshold;
	private final ArrayDeque<Long> _measuredPoints = new ArrayDeque<>();

	public TimeGauge(Logger logger, long threshold, boolean debug) {
		_logger = logger;
		_threshold = threshold;
		_debug = debug;
	}

	public TimeGauge(Logger logger) {
		this(logger, 0L, true);
	}

	private boolean isLogEnabled() {
		return _debug ? _logger.isDebugEnabled() : _logger.isInfoEnabled();
	}
	
	private void log(String msg) {
		if (_debug) _logger.debug(msg); else _logger.info(msg);
	}
	
	public void startTimeMeasurement() {
		_measuredPoints.push(System.nanoTime());
	}

	public long stopTimeMeasurement() {
		return (System.nanoTime() - _measuredPoints.pop()) / 1000000L;
	}

	public long stopTimeMeasurement(String text, boolean restart) {
		return stopTimeMeasurement(text, restart, EMPTY_OBJECT_ARRAY);
	}

	public long stopTimeMeasurement(String text, boolean restart, Object... args) {
		final long endTS = System.nanoTime();
		final long startTS = _measuredPoints.pop();
		final long diff = (endTS - startTS) / 1000000L;
		if (diff >= _threshold && isLogEnabled()) {
			if (args.length > 0) text = String.format(text, args);
			log(text + " took " + diff / 1000. + "s");
		}
		if (restart) {
			_measuredPoints.push(endTS);
		}
		return diff;
	}

	public void clear() {
		_measuredPoints.clear();
	}

}
