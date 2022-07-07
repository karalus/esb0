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
import java.net.HttpURLConnection;

public class HttpCheckAlive {

	protected Integer _retryAfter; 

	@Override
	public boolean equals(Object obj) {
		return obj != null && getClass() == obj.getClass();
	}

	public HttpURLConnection connect(HttpEndpoint httpEndpoint, int pos) throws IOException {
		HttpURLConnection conn = (HttpURLConnection) httpEndpoint.getHttpUrls().get(pos).getUrl().openConnection(httpEndpoint.getProxy());
		conn.setConnectTimeout(httpEndpoint.getConnectionTimeout());
		// SSL Handshake got stuck
		conn.setReadTimeout(10000);
		conn.setRequestMethod("HEAD");
		return conn;
	}

	public boolean isAlive(HttpURLConnection conn, int responseCode) {
		if (responseCode == HttpURLConnection.HTTP_UNAVAILABLE) {
			_retryAfter = conn.getHeaderFieldInt(HttpConstants.HTTP_HEADER_RETRY_AFTER, -1);
			if (_retryAfter < 0) {
				long retryAfter = conn.getHeaderFieldDate(HttpConstants.HTTP_HEADER_RETRY_AFTER, 0) - System.currentTimeMillis();
				if (retryAfter > 0) {
					_retryAfter = ((int) retryAfter + 999) / 1000;
				} else {
					_retryAfter = null;
				}
			}
			return false;
		}
		return true;
	}

	public final Integer consumeRetryAfter() {
		Integer retryAfter = _retryAfter;
		_retryAfter = null;
		return retryAfter;
	}

	public static class ConnectException extends IOException {

		private static final long serialVersionUID = 1L;

		ConnectException(String message) {
			super(message);
		}

	}

}
