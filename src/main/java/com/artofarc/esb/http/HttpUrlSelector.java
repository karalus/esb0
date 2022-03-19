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
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.URL;
import java.util.Collection;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.AttributeChangeNotification;
import javax.management.MBeanNotificationInfo;
import javax.management.NotificationBroadcasterSupport;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;

import com.artofarc.esb.context.WorkerPool;
import com.artofarc.util.IOUtils;

public final class HttpUrlSelector extends NotificationBroadcasterSupport implements Runnable, HttpUrlSelectorMBean {

	public final class HttpUrlConnectionWrapper {

		private final HttpUrl _httpUrl;
		private final int _pos;
		private final HttpURLConnection _httpURLConnection;

		private HttpUrlConnectionWrapper(HttpUrl httpUrl, int pos, HttpURLConnection httpURLConnection) {
			_httpUrl = httpUrl;
			_pos = pos;
			_httpURLConnection = httpURLConnection;
			inUse.incrementAndGet(pos);
		}

		public HttpUrl getHttpUrl() {
			return _httpUrl;
		}

		public HttpURLConnection getHttpURLConnection() {
			return _httpURLConnection;
		}

		public int getResponseCode() throws IOException {
			int responseCode = _httpURLConnection.getResponseCode();
			HttpCheckAlive httpCheckAlive = _httpEndpoint.getHttpCheckAlive();
			if (httpCheckAlive != null && !httpCheckAlive.isAlive(_httpURLConnection, responseCode)) {
				if (_httpEndpoint.getCheckAliveInterval() != null) {
					setActive(_pos, false);
				}
				if (_httpURLConnection.getErrorStream() != null) {
					// Consume error message
					IOUtils.copy(_httpURLConnection.getErrorStream());
				}
				throw new HttpCheckAlive.ConnectException(_httpUrl.getUrlStr() + " is not alive. Response code " + responseCode);
			}
			return responseCode;
		}

		public void close() {
			inUse.decrementAndGet(_pos);
		}
	}

	private HttpEndpoint _httpEndpoint;
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

	public HttpUrlSelector(HttpEndpoint httpEndpoint, WorkerPool workerPool) {
		super(workerPool.getExecutorService(), new MBeanNotificationInfo(new String[] { AttributeChangeNotification.ATTRIBUTE_CHANGE },
				AttributeChangeNotification.class.getName(), "An endpoint of " + httpEndpoint.getName() + " changes its state"));

		_httpEndpoint = httpEndpoint;
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

	public synchronized HttpEndpoint getHttpEndpoint() {
		return _httpEndpoint;
	}

	public synchronized void setHttpEndpoint(HttpEndpoint httpEndpoint) {
		_httpEndpoint = httpEndpoint;
	}

	public synchronized boolean isActive(int pos) {
		return active[pos];
	}

	public synchronized void setActive(int pos, boolean b) {
		boolean old = active[pos];
		if (b != old) {
			active[pos] = b;
			if (b) {
				if (++activeCount == size) {
					_future.cancel(true);
					_future = null;
				}
			} else {
				--activeCount;
				if (_future == null && _httpEndpoint.getCheckAliveInterval() != null) {
					_future = _workerPool.getScheduledExecutorService().scheduleWithFixedDelay(this, _httpEndpoint.getCheckAliveInterval(), _httpEndpoint.getCheckAliveInterval(), TimeUnit.SECONDS);
				}
			}
			sendNotification(new AttributeChangeNotification(this, ++_sequenceNumber, System.currentTimeMillis(), "Endpoint state changed", "active[" + pos + "]", "boolean", old, b));
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
		for (int i = 0; i < size; ++i) {
			if (!isActive(i)) {
				try {
					HttpURLConnection conn = _httpEndpoint.getHttpCheckAlive().connect(_httpEndpoint, i);
					if (_httpEndpoint.getHttpCheckAlive().isAlive(conn, conn.getResponseCode())) {
						setActive(i, true);
					}
				} catch (IOException e) {
					// ignore
				}
			}
		}
	}

	public HttpUrlConnectionWrapper connectTo(HttpEndpoint httpEndpoint, int timeout, String method, String appendUrl, Collection<Entry<String, Object>> headers, Integer chunkLength, Long contentLength) throws IOException {
		return connectTo(httpEndpoint, timeout, method, appendUrl, headers, chunkLength, contentLength, httpEndpoint.getRetries());
	}

	private HttpUrlConnectionWrapper connectTo(HttpEndpoint httpEndpoint, int timeout, String method, String appendUrl, Collection<Entry<String, Object>> headers, Integer chunkLength, Long contentLength, int retryCount) throws IOException {
		int pos = computeNextPos();
		if (pos < 0) {
			throw new ConnectException("No active url");
		}
		try {
			HttpUrl httpUrl = httpEndpoint.getHttpUrls().get(pos);
			return new HttpUrlConnectionWrapper(httpUrl, pos, connectTo(httpUrl, timeout, method, appendUrl, headers, chunkLength, contentLength));
		} catch (ConnectException | NoRouteToHostException e) {
			if (_httpEndpoint.getCheckAliveInterval() != null) {
				setActive(pos, false);
			}
			if (retryCount > 0) {
				return connectTo(httpEndpoint, timeout, method, appendUrl, headers, chunkLength, contentLength, --retryCount);
			}
			throw e;
		}
	}

	private synchronized int computeNextPos() {
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
						weight[pos] = _httpEndpoint.getHttpUrls().get(pos).getWeight();
						return pos++;
					}
				}
				break;
			}
		}
	}

	private HttpURLConnection connectTo(HttpUrl httpUrl, int timeout, String method, String appendUrl, Collection<Entry<String, Object>> headers, Integer chunkLength, Long contentLength) throws IOException {
		URL url = appendUrl != null && appendUrl.length() > 0 ? new URL(httpUrl.getUrlStr() + appendUrl) : httpUrl.getUrl();
		HttpURLConnection conn = (HttpURLConnection) url.openConnection(_httpEndpoint.getProxy());
		conn.setConnectTimeout(_httpEndpoint.getConnectionTimeout());
		conn.setReadTimeout(timeout);
		// For "PATCH" refer to https://stackoverflow.com/questions/25163131/httpurlconnection-invalid-http-method-patch
		if (method.equals("PATCH")) {
			conn.setRequestProperty(HttpConstants.HTTP_HEADER_X_METHOD_OVERRIDE, "PATCH");
			conn.setRequestMethod("POST");		
		} else {
			conn.setRequestMethod(method);
		}
		conn.setDoOutput(true);
		conn.setInstanceFollowRedirects(false);
		if (chunkLength != null) {
			conn.setChunkedStreamingMode(chunkLength);
		} else if (contentLength != null) {
			conn.setFixedLengthStreamingMode(contentLength);
		}
		for (Entry<String, Object> entry : headers) {
			if (entry.getValue() != null) {
				conn.setRequestProperty(entry.getKey(), entry.getValue().toString());
			}
		}
		conn.connect();
		_totalConnectionsCount.incrementAndGet();
		return conn;
	}

	// Methods for monitoring, not synchronized to avoid effects on important methods

	public CompositeDataSupport[] getHttpEndpointStates() throws OpenDataException {
		String[] itemNames = { "URL", "weight", "active", "inUse" };
		OpenType<?>[] itemTypes = { SimpleType.STRING, SimpleType.INTEGER, SimpleType.BOOLEAN, SimpleType.INTEGER };
		CompositeType rowType = new CompositeType("HttpEndpointState", "State of HttpEndpoint", itemNames, itemNames, itemTypes);

		CompositeDataSupport[] result = new CompositeDataSupport[size];
		for (int i = 0; i < size; ++i) {
			Object[] itemValues = { _httpEndpoint.getHttpUrls().get(i).toString(), weight[i], isActive(i), inUse.get(i) };
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

	public void evict() {
		_workerPool.getPoolContext().getGlobalContext().getHttpEndpointRegistry().evictHttpUrlSelector(_httpEndpoint);
	}

}
