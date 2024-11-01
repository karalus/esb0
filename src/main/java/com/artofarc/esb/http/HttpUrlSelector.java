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
import java.lang.ref.WeakReference;
import java.net.CookieManager;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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

import com.artofarc.esb.context.WorkerPool;

public abstract class HttpUrlSelector extends NotificationBroadcasterSupport implements Runnable, HttpUrlSelectorMBean {

	private final List<WeakReference<HttpEndpoint>> _httpEndpoints = new ArrayList<>();
	private final WorkerPool _workerPool;
	protected final int size;
	private final int[] weight;
	private final boolean[] active;
	protected final AtomicIntegerArray inUse;
	private int pos;
	protected int activeCount;
	private long _sequenceNumber;
	private ScheduledFuture<?> _future;
	protected final AtomicLong _totalConnectionsCount = new AtomicLong();

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

	public static boolean doOutput(String method, Long contentLength) {
		switch (method) {
		case "POST":
		case "PUT":
		case "PATCH":
			return contentLength == null || contentLength > 0;
		case "DELETE":
			// May have body (https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/DELETE)
			return contentLength != null && contentLength > 0;
		default:
			return false;
		}
	}

	final boolean missesHttpEndpoint(HttpEndpoint httpEndpoint) {
		synchronized (_httpEndpoints) {
			for (WeakReference<HttpEndpoint> ref : _httpEndpoints) {
				if (ref.get() == httpEndpoint) {
					return false;
				}
			}
		}
		return true;
	}

	public final HttpEndpoint getFirstHttpEndpoint() {
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

	final void addHttpEndpoint(HttpEndpoint httpEndpoint) {
		synchronized (_httpEndpoints) {
			HttpEndpoint pivot = getFirstHttpEndpoint();
			if (pivot != null && pivot.getModificationTime() < httpEndpoint.getModificationTime()) {
				// take non diversifying parameters from most recent version
				_httpEndpoints.add(0, new WeakReference<>(httpEndpoint));
			} else {
				_httpEndpoints.add(new WeakReference<>(httpEndpoint));
			}
		}
	}

	public final synchronized boolean isActive(int pos) {
		return active[pos];
	}

	public final synchronized void setActive(int pos, boolean b) {
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
		HttpEndpoint httpEndpoint = getFirstHttpEndpoint();
		if (httpEndpoint != null && httpEndpoint.getCheckAliveInterval() != null) {
			Integer retryAfter = httpEndpoint.getHttpCheckAlive().consumeRetryAfter();
			int nextCheckAlive = retryAfter != null ? retryAfter : httpEndpoint.getCheckAliveInterval();
			_future = _workerPool.getScheduledExecutorService().schedule(this, nextCheckAlive, TimeUnit.SECONDS);
		}
	}

	public final synchronized void stop() {
		if (_future != null) {
			_future.cancel(true);
			_future = null;
		}
	}

	@Override
	public final void run() {
		HttpEndpoint httpEndpoint = getFirstHttpEndpoint();
		if (httpEndpoint != null) {
			for (int i = 0; i < size; ++i) {
				if (!isActive(i)) {
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
		}
		synchronized (this) {
			if (activeCount < size) {
				scheduleHealthCheck();
			}
		}
	}

	protected abstract boolean checkAlive(HttpEndpoint httpEndpoint, HttpUrl httpUrl) throws Exception;

	synchronized int computeNextPos(HttpEndpoint httpEndpoint) {
		for (int i = 0;; ++pos) {
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
					if (httpEndpoint.isMultiThreaded()) {
						if (--weight[pos] == 0) {
							weight[pos] = httpEndpoint.getHttpUrls().get(pos).getWeight();
							return pos++;
						}
					} else if (i++ == activeCount || inUse.get(pos) < weight[pos]) {
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
			Object[] itemValues = { getFirstHttpEndpoint().getHttpUrls().get(i).toString(), weight[i], isActive(i), inUse.get(i) };
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
		_workerPool.getPoolContext().getGlobalContext().getHttpEndpointRegistry().evictHttpUrlSelector(getFirstHttpEndpoint());
	}

	public String getCookies() throws Exception {
		CookieManager cookieManager = _workerPool.getPoolContext().getGlobalContext().getHttpGlobalContext().getCookieManager();
		if (cookieManager != null) {
			HttpUrl httpUrl = getFirstHttpEndpoint().getHttpUrls().get(0);
			return cookieManager.getCookieStore().get(httpUrl.getURI()).stream().map(c -> c.toString()).collect(Collectors.joining(", "));
		}
		return null;
	}

}
