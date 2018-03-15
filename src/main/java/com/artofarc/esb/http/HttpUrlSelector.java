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
package com.artofarc.esb.http;

import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.artofarc.esb.context.WorkerPool;

public class HttpUrlSelector implements Runnable {
	
	private HttpEndpoint _httpEndpoint;
	
	private final WorkerPool _workerPool;

	private final int size;
	private final int[] weight;
	private final boolean[] active;
	
	private int pos;
	private int activeCount;

	private ScheduledFuture<?> _future;
	
	public HttpUrlSelector(HttpEndpoint httpEndpoint, WorkerPool workerPool) {
		_httpEndpoint = httpEndpoint;
		_workerPool = workerPool;
		size = httpEndpoint.getHttpUrls().size();
		weight = new int[size];
		active = new boolean[size];
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

	private synchronized int computeNextPos() {
		for (;; ++pos) {
			if (pos == size) {
				pos = 0;
			}
			if (active[pos]) {
				if (--weight[pos] == 0) {
					weight[pos] = _httpEndpoint.getHttpUrls().get(pos).getWeight();
					return pos++;
				}
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
		}
	}

	@Override
	public void run() {
		for (int i = 0; i < size; ++i) {
			if (!isActive(i)) {
				try {
					HttpURLConnection conn = (HttpURLConnection) _httpEndpoint.getHttpUrls().get(i).getUrl().openConnection();
					conn.setConnectTimeout(_httpEndpoint.getConnectionTimeout());
					conn.connect();
					setActive(i, true);
				} catch (IOException e) {
					// ignore
				}
			}
		}
	}
	
	public HttpURLConnection connectTo(HttpEndpoint httpEndpoint, String method, String spec, Set<Entry<String, Object>> headers, boolean doOutput, Integer chunkLength) throws IOException {
		return connectTo(httpEndpoint, method, spec, headers, doOutput, chunkLength, httpEndpoint.getRetries());
	}
	
	private HttpURLConnection connectTo(HttpEndpoint httpEndpoint, String method, String spec, Set<Entry<String, Object>> headers, boolean doOutput, Integer chunkLength, int retryCount) throws IOException {
		if (activeCount == 0) {
			throw new ConnectException("No active url");
		}
		int pos = computeNextPos();
		try {
			return connectTo(httpEndpoint.getHttpUrls().get(pos).getUrl(), method, spec, headers, doOutput, chunkLength);
		} catch (IOException e) {
			if (_httpEndpoint.getCheckAliveInterval() != null) {
				setActive(pos, false);
			}
			if (retryCount > 0) {
				return connectTo(httpEndpoint, method, spec, headers, doOutput, chunkLength, --retryCount);
			}
			throw e;
		}
	}

	private HttpURLConnection connectTo(URL url, String method, String spec, Set<Entry<String, Object>> headers, boolean doOutput, Integer chunkLength) throws IOException {
		if (spec != null) {
			url = new URL(url, spec);
		}
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setConnectTimeout(_httpEndpoint.getConnectionTimeout());
		conn.setRequestMethod(method);
		conn.setDoOutput(doOutput);
		if (chunkLength != null) {
			conn.setChunkedStreamingMode(chunkLength);
		}
		for (Entry<String, Object> entry : headers) {
			conn.addRequestProperty(entry.getKey(), entry.getValue().toString());
		}
		conn.connect();
		return conn;
	}

}
