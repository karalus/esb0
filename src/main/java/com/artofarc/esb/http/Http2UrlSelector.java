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

import java.io.InputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiFunction;

import com.artofarc.esb.context.WorkerPool;

public final class Http2UrlSelector extends HttpUrlSelector {

	private final HttpClient _httpClient; 

	public Http2UrlSelector(HttpEndpoint httpEndpoint, WorkerPool workerPool) {
		super(httpEndpoint, workerPool);
		HttpGlobalContext httpGlobalContext = workerPool.getPoolContext().getGlobalContext().getHttpGlobalContext();
		HttpClient.Builder builder = HttpClient.newBuilder().proxy(httpGlobalContext).version(httpEndpoint.getVersion()).connectTimeout(Duration.ofMillis(httpEndpoint.getConnectTimeout()));
		if (httpGlobalContext.getCookieManager() != null) {
			builder.cookieHandler(httpGlobalContext.getCookieManager());
		}
		if (httpEndpoint.getSSLContext() != null) {
			builder.sslContext(httpEndpoint.getSSLContext());
		}
		_httpClient = builder.build();
	}

	@Override
	protected boolean checkAlive(HttpEndpoint httpEndpoint, HttpUrl httpUrl) throws Exception {
		URI uri = new URI(httpUrl.getUrlStr());
		HttpRequest request = HttpRequest.newBuilder(uri).method(httpEndpoint.getHttpCheckAlive().getCheckAliveMethod(), HttpRequest.BodyPublishers.noBody())
				// Real life experience: SSL Handshake got stuck forever without timeout
				.timeout(Duration.ofMillis(httpEndpoint.getConnectTimeout())).build();
		HttpResponse<String> httpResponse = _httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		return httpEndpoint.getHttpCheckAlive().isAlive(httpResponse.statusCode(), (name) -> httpResponse.headers().firstValue(name).orElse(null));
	}

	public HttpResponse<InputStream> send(HttpEndpoint httpEndpoint, HttpRequest.Builder requestBuilder, String appendUrl, boolean doOutput, CountDownLatch streamConsumed) throws Exception {
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
				if (httpCheckAlive != null && !httpCheckAlive.isAlive(httpResponse.statusCode(), (name) -> httpResponse.headers().firstValue(name).orElse(null))) {
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
			BiFunction<HttpResponse<InputStream>, Throwable, Void> fn, WorkerPool workerPool) {

		sendAsync(httpEndpoint, requestBuilder, appendUrl, doOutput, streamConsumed, fn, workerPool, HttpResponse.BodyHandlers.ofInputStream(), httpEndpoint.getRetries());
	}

	private void sendAsync(HttpEndpoint httpEndpoint, HttpRequest.Builder requestBuilder, String appendUrl, boolean doOutput, CountDownLatch streamConsumed,
			BiFunction<HttpResponse<InputStream>, Throwable, Void> fn, WorkerPool workerPool, HttpResponse.BodyHandler<InputStream> bodyHandler, int retryCount) {

		int pos = computeNextPos(httpEndpoint);
		if (pos < 0) {
			fn.apply(null, new ConnectException("No active url"));
			return;
		}
		String urlStr = httpEndpoint.getHttpUrls().get(pos).getUrlStr();
		URI uri;
		try {
			uri = new URI(urlStr + appendUrl);
		} catch (URISyntaxException e) {
			fn.apply(null, e);
			return;
		}
		// check whether server is willing to respond (before sending data)
		boolean checkServer = retryCount > size - activeCount;
		HttpRequest request = requestBuilder.expectContinue(checkServer && doOutput).uri(uri).build();
		inUse.incrementAndGet(pos);
		_httpClient.sendAsync(request, bodyHandler).handleAsync((httpResponse, completionException) -> {
			inUse.decrementAndGet(pos);
			boolean retry;
			Throwable exc;
			if (httpResponse != null) {
				HttpCheckAlive httpCheckAlive = httpEndpoint.getHttpCheckAlive();
				retry = httpCheckAlive != null && !httpCheckAlive.isAlive(httpResponse.statusCode(), (name) -> httpResponse.headers().firstValue(name).orElse(null));
				exc = retry ? new HttpCheckAlive.ConnectException(urlStr + " is not alive. Response code " + httpResponse.statusCode()) : null;
			} else {
				exc = completionException.getCause();
				retry = exc instanceof ConnectException || exc instanceof NoRouteToHostException;
			}
			if (retry) {
				if (httpEndpoint.getCheckAliveInterval() != null) {
					setActive(pos, false);
				}
				if (retryCount > 0 && (streamConsumed == null || streamConsumed.getCount() > 0)) {
					sendAsync(httpEndpoint, requestBuilder, appendUrl, doOutput, streamConsumed, fn, workerPool, bodyHandler, retryCount - 1);
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
