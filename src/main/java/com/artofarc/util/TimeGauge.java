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
package com.artofarc.util;

import java.util.Stack;

import org.slf4j.Logger;

public final class TimeGauge implements AutoCloseable {

	private final Logger _logger; 
	private final long _threshold;
	private final Stack<Long> timeMeasurement = new Stack<>();

	public TimeGauge(Logger logger, long threshold) {
		_logger = logger;
		_threshold = threshold;
	}

	public TimeGauge(Logger logger) {
		this(logger, 0L);
	}

	public void startTimeMeasurement() {
		timeMeasurement.push(System.currentTimeMillis());
	}

	public void stopTimeMeasurement() {
		timeMeasurement.pop();
	}

	public void stopTimeMeasurement(String text, boolean restart, Object... args) {
		final long endTS = System.currentTimeMillis();
		final long startTS = timeMeasurement.pop();
		final long diff = endTS - startTS;
		if (diff >= _threshold && _logger.isInfoEnabled()) {
			if (args.length > 0) text = String.format(text, args);
			_logger.info(String.format(text, args) + " took " + diff / 1000. + "s");
		}
		if (restart) {
			timeMeasurement.push(endTS);
		}
	}

	@Override
	public void close() {
		timeMeasurement.clear();
	}

	public AutoCloseable createTimer(final String message) {
		startTimeMeasurement();
		return new AutoCloseable() {

			@Override
			public void close() {
				stopTimeMeasurement(message, false);
			}
		};
	}

}
