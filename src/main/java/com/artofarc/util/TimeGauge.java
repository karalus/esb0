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
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TimeGauge implements AutoCloseable {

	protected final static Logger logger = Logger.getLogger("ESB");

	private final Stack<Long> timeMeasurement = new Stack<Long>();

	private final Level _level;

	public TimeGauge(Level level) {
		_level = level;
	}

	public void startTimeMeasurement() {
		timeMeasurement.push(System.currentTimeMillis());
	}

	public void stopTimeMeasurement() {
		timeMeasurement.pop();
	}

	public void stopTimeMeasurement(String text, boolean restart) {
		long startTS = timeMeasurement.pop();
		long endTS = System.currentTimeMillis();
		logger.log(_level, text + " took " + (endTS - startTS) / 1000. + "s");
		if (restart)
			timeMeasurement.push(endTS);
	}

	@Override
	public void close() throws Exception {
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
