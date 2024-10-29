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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

import com.artofarc.esb.context.WorkerPool;
import com.artofarc.util.IOUtils;

public final class Http1UrlSelector extends HttpUrlSelector {

	public final class HttpUrlConnection {

		private final HttpEndpoint _httpEndpoint;
		private final int _pos;
		private final HttpURLConnection _httpURLConnection;
		private final OutputStream _outputStream;
		private int _responseCode = -1;

		HttpUrlConnection(HttpEndpoint httpEndpoint, int pos, HttpURLConnection httpURLConnection, OutputStream outputStream) {
			_httpEndpoint = httpEndpoint;
			_pos = pos;
			_httpURLConnection = httpURLConnection;
			_outputStream = outputStream;
			inUse.incrementAndGet(pos);
		}

		public HttpUrl getHttpUrl() {
			return _httpEndpoint.getHttpUrls().get(_pos);
		}

		public OutputStream getOutputStream() throws IOException {
			return _outputStream != null ? _outputStream : _httpURLConnection.getOutputStream();
		}

		public int getResponseCode() throws IOException {
			if (_responseCode < 0) {
				_responseCode = _httpURLConnection.getResponseCode();
				HttpCheckAlive httpCheckAlive = _httpEndpoint.getHttpCheckAlive();
				if (httpCheckAlive != null && !httpCheckAlive.isAlive(_responseCode, name -> _httpURLConnection.getHeaderField(name))) {
					if (_httpEndpoint.getCheckAliveInterval() != null) {
						setActive(_pos, false);
					}
					if (_httpURLConnection.getErrorStream() != null) {
						// Consume error message
						IOUtils.toByteArray(_httpURLConnection.getErrorStream());
					}
					throw new HttpCheckAlive.ConnectException(getHttpUrl().getUrlStr() + " is not alive. Response code " + _responseCode);
				}
			}
			return _responseCode;
		}

		public Map<String, List<String>> getHeaders() {
			return _httpURLConnection.getHeaderFields();
		}

		public InputStream getInputStream() throws IOException {
			return getResponseCode() < HttpURLConnection.HTTP_BAD_REQUEST ? _httpURLConnection.getInputStream() : _httpURLConnection.getErrorStream();
		}

		public void close() {
			inUse.decrementAndGet(_pos);
		}
	}

	Http1UrlSelector(HttpEndpoint httpEndpoint, WorkerPool workerPool) {
		super(httpEndpoint, workerPool);
	}

	@Override
	protected boolean checkAlive(HttpEndpoint httpEndpoint, HttpUrl httpUrl) throws IOException {
		return checkAlive(httpEndpoint, httpUrl, httpEndpoint.getHttpCheckAlive());
	}

	public static boolean checkAlive(HttpEndpoint httpEndpoint, HttpUrl httpUrl, HttpCheckAlive httpCheckAlive) throws IOException {
		HttpURLConnection conn = createHttpURLConnection(httpEndpoint, httpUrl.getURL());
		// Real life experience: SSL Handshake got stuck forever without timeout
		conn.setReadTimeout(httpEndpoint.getConnectTimeout());
		conn.setRequestMethod(httpCheckAlive.getCheckAliveMethod());
		return httpCheckAlive.isAlive(conn.getResponseCode(), name -> conn.getHeaderField(name));
	}

	private static HttpURLConnection createHttpURLConnection(HttpEndpoint httpEndpoint, URL url) throws IOException {
		HttpURLConnection conn = (HttpURLConnection) url.openConnection(httpEndpoint.getProxy());
		if (httpEndpoint.getSSLContext() != null) {
			((HttpsURLConnection) conn).setSSLSocketFactory(httpEndpoint.getSSLContext().getSocketFactory());
		}
		conn.setConnectTimeout(httpEndpoint.getConnectTimeout());
		return conn;
	}

	public HttpUrlConnection connectTo(HttpEndpoint httpEndpoint, int timeout, String method, String appendUrl, Collection<Map.Entry<String, Object>> headers, Integer chunkLength, Long contentLength) throws IOException {
		for (int retryCount = httpEndpoint.getRetries();;) {
			int pos = computeNextPos(httpEndpoint);
			if (pos < 0) {
				throw new ConnectException("No active url");
			}
			HttpUrl httpUrl = httpEndpoint.getHttpUrls().get(pos);
			URL url = appendUrl != null && appendUrl.length() > 0 ? new URL(httpUrl.getUrlStr() + appendUrl) : httpUrl.getURL();
			HttpUrlConnection httpUrlConnection = null;
			try {
				HttpURLConnection conn = createHttpURLConnection(httpEndpoint, url);
				conn.setReadTimeout(timeout);
				// For "PATCH" refer to https://stackoverflow.com/questions/25163131/httpurlconnection-invalid-http-method-patch
				if ("PATCH".equals(method)) {
					conn.setRequestProperty(HttpConstants.HTTP_HEADER_X_METHOD_OVERRIDE, "PATCH");
					conn.setRequestMethod("POST");		
				} else {
					conn.setRequestMethod(method);
				}
				if (contentLength == null || contentLength > 0) {
					conn.setDoOutput(true);
					if (chunkLength != null) {
						conn.setChunkedStreamingMode(chunkLength);
					} else if (contentLength != null) {
						conn.setFixedLengthStreamingMode(contentLength);
					}
				}
				conn.setInstanceFollowRedirects(false);
				for (Map.Entry<String, Object> entry : headers) {
					if (entry.getValue() instanceof List) {
						@SuppressWarnings("unchecked")
						List<String> values = (List<String>) entry.getValue();
						for (String value : values) {
							conn.addRequestProperty(entry.getKey(), value);
						}
					} else {
						conn.setRequestProperty(entry.getKey(), entry.getValue().toString());
					}
				}
				// check whether server is willing to respond (before sending data)
				boolean checkServer = retryCount > size - activeCount;
				if (checkServer && conn.getDoOutput()) {
					conn.setRequestProperty("Expect", "100-Continue");
					if (chunkLength == null && contentLength == null) {
						conn.setChunkedStreamingMode(0);
					}
					httpUrlConnection = new HttpUrlConnection(httpEndpoint, pos, conn, conn.getOutputStream());
				} else {
					httpUrlConnection = new HttpUrlConnection(httpEndpoint, pos, conn, null);
					if (checkServer && !conn.getDoOutput()) {
						httpUrlConnection.getResponseCode();
					} else {
						conn.connect();
					}
				}
				_totalConnectionsCount.incrementAndGet();
				return httpUrlConnection;
			} catch (IOException e) {
				if (httpEndpoint.getCheckAliveInterval() != null) {
					setActive(pos, false);
				}
				if (httpUrlConnection != null) {
					httpUrlConnection.close();
				}
				if (--retryCount < 0) {
					throw e;
				}
			}
		}
	}

}
