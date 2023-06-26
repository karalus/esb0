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
package com.artofarc.esb.http;

import java.io.IOException;
import java.util.Date;
import java.util.function.Function;

public class HttpCheckAlive {

	@SuppressWarnings("deprecation")
	protected static long convertDateString(String dateString) {
		try {
			if (dateString.indexOf("GMT") < 0) {
				dateString += " GMT";
			}
			return Date.parse(dateString);
		} catch (Exception e) {
			return -1;
		}
	}

	protected Integer _retryAfter; 

	@Override
	public boolean equals(Object obj) {
		return obj != null && getClass() == obj.getClass();
	}

	public String getCheckAliveMethod() {
		return "HEAD";
	}

	public boolean isAlive(int statusCode, Function<String, String> getHeader) {
		if (statusCode == 503) {
			_retryAfter = null;
			String retryAfter = getHeader.apply(HttpConstants.HTTP_HEADER_RETRY_AFTER);
			if (retryAfter != null) {
				try {
					_retryAfter = Integer.valueOf(retryAfter);
				} catch (NumberFormatException e) {
					long date = convertDateString(retryAfter);
					if (date > 0) {
						_retryAfter = (int) (date - System.currentTimeMillis() + 999) / 1000;
					}
				}
			}
			return false;
		}
		return true;
	}

	final Integer consumeRetryAfter() {
		Integer retryAfter = _retryAfter;
		_retryAfter = null;
		return retryAfter;
	}

	public static final class ConnectException extends IOException {

		private static final long serialVersionUID = 1L;

		public ConnectException(String message) {
			super(message);
		}
	}

}
