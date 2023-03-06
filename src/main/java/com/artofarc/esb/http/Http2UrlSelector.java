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
package com.artofarc.esb.http;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.ConnectException;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

import javax.management.AttributeChangeNotification;
import javax.management.MBeanNotificationInfo;
import javax.management.NotificationBroadcasterSupport;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.WorkerPool;

public final class Http2UrlSelector extends NotificationBroadcasterSupport implements Runnable {

	private final HttpClient _httpClient; 

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

	public Http2UrlSelector(HttpEndpoint httpEndpoint, WorkerPool workerPool) {
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
		_httpClient = getHttpClient(workerPool.getPoolContext().getGlobalContext(), httpEndpoint);
	}

	private HttpClient getHttpClient(GlobalContext globalContext, HttpEndpoint _httpEndpoint) {
		HttpClient.Builder builder = HttpClient.newBuilder().proxy(globalContext.getHttpGlobalContext()).connectTimeout(Duration.ofMillis(_httpEndpoint.getConnectionTimeout()));
		CookieManager cookieManager = globalContext.getHttpGlobalContext().getCookieManager();
		if (cookieManager != null) {
			builder.cookieHandler(cookieManager);
		}
		if (_httpEndpoint.getSSLContext() != null) {
			builder.sslContext(_httpEndpoint.getSSLContext());
		}
		return builder.build();
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
				try {
					HttpURLConnection conn = httpEndpoint.getHttpCheckAlive().connect(httpEndpoint, i);
					if (httpEndpoint.getHttpCheckAlive().isAlive(conn, conn.getResponseCode())) {
						setActive(i, true);
					}
				} catch (IOException e) {
					// ignore
				}
			}
		}
		synchronized (this) {
			if (activeCount < size) {
				scheduleHealthCheck();
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

	public HttpResponse<InputStream> send(HttpEndpoint httpEndpoint, HttpRequest.Builder requestBuilder, String appendUrl, boolean doOutput, CountDownLatch streamConsumed) throws URISyntaxException, IOException, InterruptedException {
		HttpResponse.BodyHandler<InputStream> bodyHandler = HttpResponse.BodyHandlers.ofInputStream();
		HttpCheckAlive httpCheckAlive = httpEndpoint.getHttpCheckAlive();
		for (int retryCount = httpEndpoint.getRetries();;) {
			int pos = computeNextPos(httpEndpoint);
			if (pos < 0) {
				throw new ConnectException("No active url");
			}
			URI uri = new URI(httpEndpoint.getHttpUrls().get(pos).getUrlStr() + appendUrl);
			// check whether server is willing to respond (before sending data)
			boolean checkServer = retryCount > size - activeCount;
			requestBuilder.expectContinue(checkServer && doOutput);
			inUse.incrementAndGet(pos);
			try {
				HttpResponse<InputStream> httpResponse = _httpClient.send(requestBuilder.uri(uri).build(), bodyHandler);
				if (httpCheckAlive != null && !httpCheckAlive.isAlive(httpResponse.headers().map(), httpResponse.statusCode())) {
					if (retryCount > 0 && streamConsumed != null && streamConsumed.getCount() == 0) {
						// body was streamed, data is gone, cannot retry
						retryCount = 0;
					}
					throw new HttpCheckAlive.ConnectException(uri + " is not alive. Response code " + httpResponse.statusCode());
				}
				_totalConnectionsCount.incrementAndGet();
				return httpResponse;
			} catch (ConnectException | NoRouteToHostException | HttpCheckAlive.ConnectException e) {
				if (httpEndpoint.getCheckAliveInterval() != null) {
					setActive(pos, false);
				}
				if (--retryCount < 0) {
					throw e;
				}
			} finally {
				inUse.decrementAndGet(pos);
			}
		}
	}

	public void sendAsync(HttpEndpoint httpEndpoint, HttpRequest.Builder requestBuilder, String appendUrl, boolean doOutput, CountDownLatch streamConsumed,
			BiFunction<HttpResponse<InputStream>, Throwable, Void> fn, WorkerPool workerPool) throws URISyntaxException, IOException {

		sendAsync(httpEndpoint, requestBuilder, appendUrl, doOutput, streamConsumed, fn, workerPool, httpEndpoint.getRetries());
	}

	private void sendAsync(HttpEndpoint httpEndpoint, HttpRequest.Builder requestBuilder, String appendUrl, boolean doOutput, CountDownLatch streamConsumed,
			BiFunction<HttpResponse<InputStream>, Throwable, Void> fn, WorkerPool workerPool, int retryCount) throws URISyntaxException, IOException {

		HttpResponse.BodyHandler<InputStream> bodyHandler = HttpResponse.BodyHandlers.ofInputStream();
		HttpCheckAlive httpCheckAlive = httpEndpoint.getHttpCheckAlive();
		int pos = computeNextPos(httpEndpoint);
		if (pos < 0) {
			throw new ConnectException("No active url");
		}
		String urlStr = httpEndpoint.getHttpUrls().get(pos).getUrlStr();
		URI uri = new URI(urlStr + appendUrl);
		// check whether server is willing to respond (before sending data)
		boolean checkServer = retryCount > size - activeCount;
		HttpRequest request = requestBuilder.expectContinue(checkServer && doOutput).uri(uri).build();
		inUse.incrementAndGet(pos);
		_httpClient.sendAsync(request, bodyHandler).handleAsync((httpResponse, completionException) -> {
			inUse.decrementAndGet(pos);
			boolean retryable;
			Throwable exc;
			if (httpResponse != null) {
				retryable = httpCheckAlive != null && !httpCheckAlive.isAlive(httpResponse.headers().map(), httpResponse.statusCode());
				exc = retryable ? new HttpCheckAlive.ConnectException(urlStr + " is not alive. Response code " + httpResponse.statusCode()) : null;
			} else {
				exc = completionException.getCause();
				retryable = exc instanceof ConnectException || exc instanceof NoRouteToHostException;
			}
			if (retryable) {
				if (httpEndpoint.getCheckAliveInterval() != null) {
					setActive(pos, false);
				}
				if (retryCount > 0 && (streamConsumed == null || streamConsumed.getCount() > 0)) {
					try {
						sendAsync(httpEndpoint, requestBuilder, appendUrl, doOutput, streamConsumed, fn, workerPool, retryCount - 1);
					} catch (URISyntaxException | IOException e) {
						fn.apply(null, e);
					}
				} else {
					fn.apply(null, exc);
				}
			} else {
				fn.apply(httpResponse, exc);
				if (exc == null) {
					_totalConnectionsCount.incrementAndGet();
				}
			}
			return null;
		}, workerPool.getExecutorService());
	}

}
