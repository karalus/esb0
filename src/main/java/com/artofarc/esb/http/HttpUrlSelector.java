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
import java.lang.ref.WeakReference;
import java.net.ConnectException;
import java.net.CookieStore;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import javax.management.AttributeChangeNotification;
import javax.management.MBeanNotificationInfo;
import javax.management.NotificationBroadcasterSupport;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.net.ssl.HttpsURLConnection;

import com.artofarc.esb.context.WorkerPool;
import com.artofarc.util.IOUtils;

public final class HttpUrlSelector extends NotificationBroadcasterSupport implements Runnable, HttpUrlSelectorMBean {

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
				if (httpCheckAlive != null && !httpCheckAlive.isAlive(_responseCode, (name) -> _httpURLConnection.getHeaderField(name))) {
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

	private final List<WeakReference<HttpEndpoint>> _httpEndpoints = new ArrayList<>();
	private final WorkerPool _workerPool;
	private final int size;
	private final int[] weight;
	private final boolean[] active;
	private final AtomicIntegerArray inUse;
	private int pos;
	private int activeCount;
	private long _sequenceNumber;
	private ScheduledFuture<?> _future;
	private final AtomicLong _totalConnectionsCount = new AtomicLong();

	HttpUrlSelector(HttpEndpoint httpEndpoint, WorkerPool workerPool) {
		super(workerPool.getExecutorService(), new MBeanNotificationInfo(new String[] { AttributeChangeNotification.ATTRIBUTE_CHANGE },
				AttributeChangeNotification.class.getName(), "An endpoint of " + httpEndpoint.getName() + " changes its state"));

		_httpEndpoints.add(new WeakReference<>(httpEndpoint));
		_workerPool = workerPool;
		size = httpEndpoint.getHttpUrls().size();
		weight = new int[size];
		active = new boolean[size];
		inUse = new AtomicIntegerArray(size);
		for (int i = 0; i < size; ++i) {
			weight[i] = httpEndpoint.getHttpUrls().get(i).getWeight();
			boolean isActive = httpEndpoint.getHttpUrls().get(i).isActive();
			active[i] = isActive;
			if (isActive) ++activeCount;
		}
	}

	public boolean missesHttpEndpoint(HttpEndpoint httpEndpoint) {
		synchronized (_httpEndpoints) {
			for (WeakReference<HttpEndpoint> ref : _httpEndpoints) {
				if (ref.get() == httpEndpoint) {
					return false;
				}
			}
		}
		return true;
	}

	public HttpEndpoint getHttpEndpoint() {
		synchronized (_httpEndpoints) {
			for (Iterator<WeakReference<HttpEndpoint>> iter = _httpEndpoints.iterator(); iter.hasNext();) {
				HttpEndpoint httpEndpoint = iter.next().get();
				if (httpEndpoint != null) {
					return httpEndpoint;
				} else {
					iter.remove();
				}
			}
		}
		return null;
	}

	public void addHttpEndpoint(HttpEndpoint httpEndpoint) {
		synchronized (_httpEndpoints) {
			HttpEndpoint pivot = getHttpEndpoint();
			if (pivot != null && pivot.getModificationTime() < httpEndpoint.getModificationTime()) {
				// take non diversifying parameters from most recent version
				_httpEndpoints.add(0, new WeakReference<>(httpEndpoint));
			} else {
				_httpEndpoints.add(new WeakReference<>(httpEndpoint));
			}
		}
	}

	public synchronized boolean isActive(int pos) {
		return active[pos];
	}

	public synchronized void setActive(int pos, boolean b) {
		boolean old = active[pos];
		if (b != old) {
			active[pos] = b;
			if (b) {
				if (++activeCount == size && _future != null) {
					_future.cancel(true);
					_future = null;
				}
			} else {
				--activeCount;
				if (_future == null) {
					scheduleHealthCheck();
				}
			}
			sendNotification(new AttributeChangeNotification(this, ++_sequenceNumber, System.currentTimeMillis(), "Endpoint state changed", "active[" + pos + "]", "boolean", old, b));
		}
	}

	private void scheduleHealthCheck() {
		HttpEndpoint httpEndpoint = getHttpEndpoint();
		if (httpEndpoint != null && httpEndpoint.getCheckAliveInterval() != null) {
			Integer retryAfter = httpEndpoint.getHttpCheckAlive().consumeRetryAfter();
			int nextCheckAlive = retryAfter != null ? retryAfter : httpEndpoint.getCheckAliveInterval();
			_future = _workerPool.getScheduledExecutorService().schedule(this, nextCheckAlive, TimeUnit.SECONDS);
		}
	}

	public synchronized void stop() {
		if (_future != null) {
			_future.cancel(true);
			_future = null;
		}
	}

	@Override
	public void run() {
		HttpEndpoint httpEndpoint = getHttpEndpoint();
		for (int i = 0; i < size; ++i) {
			if (httpEndpoint != null && !isActive(i)) {
				HttpUrl httpUrl = httpEndpoint.getHttpUrls().get(i);
				try {
					if (checkAlive(httpEndpoint, httpUrl)) {
						setActive(i, true);
					}
				} catch (IOException e) {
					// ignore
				} catch (Exception e) {
					HttpEndpointRegistry.logger.error("Unexpected exception for " + httpUrl, e);
				}
			}
		}
		synchronized (this) {
			if (activeCount < size) {
				scheduleHealthCheck();
			}
		}
	}

	public boolean checkAlive(HttpEndpoint httpEndpoint, HttpUrl httpUrl) throws IOException {
		return checkAlive(httpEndpoint, httpUrl, httpEndpoint.getHttpCheckAlive());
	}

	public static boolean checkAlive(HttpEndpoint httpEndpoint, HttpUrl httpUrl, HttpCheckAlive httpCheckAlive) throws IOException {
		HttpURLConnection conn = createHttpURLConnection(httpEndpoint, httpUrl.getUrl());
		// SSL Handshake got stuck
		conn.setReadTimeout(httpEndpoint.getConnectTimeout());
		conn.setRequestMethod(httpCheckAlive.getCheckAliveMethod());
		return httpCheckAlive.isAlive(conn.getResponseCode(), (name) -> conn.getHeaderField(name));
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
			URL url = appendUrl != null && appendUrl.length() > 0 ? new URL(httpUrl.getUrlStr() + appendUrl) : httpUrl.getUrl();
			HttpUrlConnection httpUrlConnection = null;
			try {
				HttpURLConnection conn = createHttpURLConnection(httpEndpoint, url);
				conn.setReadTimeout(timeout);
				// For "PATCH" refer to https://stackoverflow.com/questions/25163131/httpurlconnection-invalid-http-method-patch
				if (method.equals("PATCH")) {
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
					if (entry.getValue() != null) {
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
			} catch (ConnectException | NoRouteToHostException | ProtocolException | HttpCheckAlive.ConnectException | SocketTimeoutException e) {
				if (httpUrlConnection != null) {
					httpUrlConnection.close();
				}
				if (httpEndpoint.getCheckAliveInterval() != null) {
					setActive(pos, false);
				}
				if (--retryCount < 0) {
					throw e;
				}
			}
		}
	}

	private synchronized int computeNextPos(HttpEndpoint httpEndpoint) {
		for (;; ++pos) {
			if (pos == size) {
				pos = 0;
			}
			switch (activeCount) {
			case 0:
				return -1;
			case 1:
				return pos;
			default:
				if (active[pos]) {
					if (--weight[pos] == 0) {
						weight[pos] = httpEndpoint.getHttpUrls().get(pos).getWeight();
						return pos++;
					}
				}
				break;
			}
		}
	}

	// Methods for monitoring, not synchronized to avoid effects on important methods

	public CompositeDataSupport[] getHttpEndpointStates() throws OpenDataException {
		String[] itemNames = { "URL", "weight", "active", "inUse" };
		OpenType<?>[] itemTypes = { SimpleType.STRING, SimpleType.INTEGER, SimpleType.BOOLEAN, SimpleType.INTEGER };
		CompositeType rowType = new CompositeType("HttpEndpointState", "State of HttpEndpoint", itemNames, itemNames, itemTypes);
		CompositeDataSupport[] result = new CompositeDataSupport[size];
		for (int i = 0; i < size; ++i) {
			Object[] itemValues = { getHttpEndpoint().getHttpUrls().get(i).toString(), weight[i], isActive(i), inUse.get(i) };
			result[i] = new CompositeDataSupport(rowType, itemNames, itemValues);
		}
		return result;
	}

	public Long getHealthCheckingDelay() {
		return _future != null ? _future.getDelay(TimeUnit.SECONDS) : null;
	}

	public int getInUseTotal() {
		int total = 0;
		for (int i = 0; i < size; ++i) {
			total += inUse.get(i);
		}
		return total; 
	}

	public long getTotalConnectionsCount() {
		return _totalConnectionsCount.get();
	}

	public int getActiveCount() {
		return activeCount;
	}

	public List<String> getAllUrls() {
		List<String> result = new ArrayList<>();
		synchronized (_httpEndpoints) {
			for (Iterator<WeakReference<HttpEndpoint>> iter = _httpEndpoints.iterator(); iter.hasNext();) {
				HttpEndpoint httpEndpoint = iter.next().get();
				if (httpEndpoint != null) {
					for (HttpUrl httpUrl : httpEndpoint.getHttpUrls()) {
						result.add(httpUrl.getUrlStr());
					}
				} else {
					iter.remove();
				}
			}
		}
		return result;
	}

	public void evict() {
		_workerPool.getPoolContext().getGlobalContext().getHttpEndpointRegistry().evictHttpUrlSelector(getHttpEndpoint());
	}

	public String getCookies() throws Exception {
		CookieStore cookieStore = _workerPool.getPoolContext().getGlobalContext().getHttpGlobalContext().getCookieStore();
		if (cookieStore != null) {
			HttpUrl httpUrl = getHttpEndpoint().getHttpUrls().get(0);
			return cookieStore.get(httpUrl.getURI()).stream().map(c -> c.toString()).collect(Collectors.joining(", "));
		}
		return null;
	}

}
