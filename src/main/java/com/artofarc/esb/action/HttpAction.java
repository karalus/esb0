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
package com.artofarc.esb.action;

import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.CookieManager;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.mail.internet.MimeMultipart;
import javax.xml.bind.DatatypeConverter;

import com.artofarc.esb.context.AsyncProcessingPool;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.WorkerPool;
import static com.artofarc.esb.http.HttpConstants.*;
import com.artofarc.esb.http.HttpEndpoint;
import com.artofarc.esb.http.HttpUrl;
import com.artofarc.esb.message.BodyType;
import static com.artofarc.esb.message.ESBConstants.*;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.MimeHelper;
import com.artofarc.util.ByteArrayOutputStream;
import com.artofarc.util.DataStructures;
import com.artofarc.util.IOUtils;
import com.artofarc.util.ReflectionUtils;
import com.artofarc.util.URLUtils;

public class HttpAction extends Action {

	private final HttpEndpoint _httpEndpoint;
	private final Integer _readTimeout;
	private final String _multipartSubtype, _multipartOption;
	private final String _workerPool;

	public HttpAction(HttpEndpoint httpEndpoint, int readTimeout, String workerPool, String multipartSubtype, String multipartOption) {
		_httpEndpoint = httpEndpoint;
		_readTimeout = readTimeout;
		_multipartSubtype = multipartSubtype;
		_multipartOption = multipartOption;
		_workerPool = workerPool;
		_pipelineStop = true;
	}

	private static void fillESBMessage(ESBMessage message, HttpResponse<InputStream> httpResponse) throws Exception {
		message.getVariables().put(HttpURLOutbound, httpResponse.uri().toString());
		message.getVariables().put(HttpResponseCode, httpResponse.statusCode());
		for (Map.Entry<String, List<String>> entry : httpResponse.headers().map().entrySet()) {
			message.putHeader(entry.getKey(), entry.getValue().get(0));
		}
		InputStream inputStream = httpResponse.body();
		message.reset(null, inputStream);
		if (message.prepareContent()) {
			inputStream.close();
		}
	}

	private HttpClient getHttpClient(GlobalContext globalContext) {
		HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(_httpEndpoint.getConnectionTimeout()));
		CookieManager cookieManager = globalContext.getHttpGlobalContext().getCookieManager();
		if (cookieManager != null) {
			builder.cookieHandler(cookieManager);
		}
		if (_httpEndpoint.getSSLContext() != null) {
			builder.sslContext(_httpEndpoint.getSSLContext());
		}
		return builder.build();
	}

	private HttpRequest createHttpRequest(Context context, ESBMessage message, HttpRequest.BodyPublisher bodyPublisher, int timeout) throws Exception {
		HttpUrl httpUrl = _httpEndpoint.getHttpUrls().get(0);
		// for REST append to URL
		String appendHttpUrl = message.getVariable(appendHttpUrlPath);
		String queryString = message.getVariable(QueryString);
		if (queryString == null || queryString.isEmpty()) {
			String httpQueryParameter = message.getVariable(HttpQueryParameter);
			if (httpQueryParameter != null) {
				queryString = URLUtils.createURLEncodedString(message.getVariables(), httpQueryParameter, ",");
			} else {
				queryString = null;
			}
		}
		if (queryString != null) {
			appendHttpUrl += "?" + queryString;
		}
		URL url = appendHttpUrl != null && appendHttpUrl.length() > 0 ? new URL(httpUrl.getUrlStr() + appendHttpUrl) : httpUrl.getUrl();
		HttpRequest.Builder builder = HttpRequest.newBuilder().uri(url.toURI()).method(message.getVariable(HttpMethod), bodyPublisher).timeout(Duration.ofMillis(timeout));
		for (Map.Entry<String, Object> entry : message.getHeaders()) {
			if (entry.getValue() != null) {
				builder.header(entry.getKey(), entry.getValue().toString());
			}
		}
		String basicAuthCredential = _httpEndpoint.getBasicAuthCredential();
		if (basicAuthCredential != null) {
			basicAuthCredential = (String) eval(basicAuthCredential, context, message);
			builder.header(HTTP_HEADER_AUTHORIZATION, "Basic " + DatatypeConverter.printBase64Binary(basicAuthCredential.getBytes(ESBMessage.CHARSET_DEFAULT)));
		}
		return builder.build();
	}

	private CompletableFuture<Void> createAsyncHandler(CompletableFuture<HttpResponse<InputStream>> completableFuture, int timeout, Context context, ESBMessage message, WorkerPool workerPool) throws Exception {
		AsyncProcessingPool asyncProcessingPool = workerPool.getAsyncProcessingPool();
		if (asyncProcessingPool == null) {
			throw new ExecutionException(this, "No AsyncProcessingPool in WorkerPool " + workerPool.getName());
		}
		asyncProcessingPool.saveContext(completableFuture, _nextAction, DataStructures.moveToNewList(context.getExecutionStack()), new ArrayList<>(context.getStackErrorHandler()),
				message.getVariables(), System.currentTimeMillis() + _httpEndpoint.getConnectionTimeout() + timeout);

		return completableFuture.handleAsync((httpResponse, exception) -> {
			ESBMessage esbMessage = exception != null ? new ESBMessage(BodyType.EXCEPTION, exception.getCause()) : new ESBMessage(BodyType.INVALID, null);
			Context workerContext = workerPool.getContext();
			Action action = workerPool.getAsyncProcessingPool().restoreContext(completableFuture, workerContext, esbMessage);
			try {
				if (httpResponse != null) {
					fillESBMessage(esbMessage, httpResponse);
					action.process(workerContext, esbMessage);
				} else {
					processException(workerContext, esbMessage);
				}
			} catch (Exception e) {
				logger.error("Exception while completing HTTP receive", e);
			} finally {
				workerPool.releaseContext(workerContext);
			}
			return null;
		}, workerPool.getExecutorService());
	}

	private CompletableFuture<HttpResponse<InputStream>> sendAsync(Context context, ESBMessage message, HttpRequest.BodyPublisher bodyPublisher, int timeout) throws Exception {
		HttpRequest httpRequest = createHttpRequest(context, message, bodyPublisher, timeout);
		return getHttpClient(context.getGlobalContext()).sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
	}

	private HttpResponse<InputStream> send(Context context, ESBMessage message, HttpRequest.BodyPublisher bodyPublisher, int timeout) throws Exception {
		HttpRequest httpRequest = createHttpRequest(context, message, bodyPublisher, timeout);
		return getHttpClient(context.getGlobalContext()).send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		message.determineSinkContentType();
		boolean async = message.getVariables().containsKey(AsyncContext);
		if (MimeHelper.isMimeMultipart(_multipartSubtype, message)) {
			if (inPipeline) {
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				message.reset(BodyType.OUTPUT_STREAM, bos);
				return new ExecutionContext(async, bos);
			}
			return new ExecutionContext(async);
		} else {
			ExecutionContext executionContext = new ExecutionContext(async);;
			Long contentLength = inPipeline ? null : message.getOutputLength();
			int timeout = message.getTimeleft(_readTimeout).intValue();
			WorkerPool workerPool = context.getGlobalContext().getWorkerPool(_workerPool);
			// check, if we need a pipe
			boolean usePipe = inPipeline || (contentLength == null && message.getBodyType() != BodyType.INPUT_STREAM);
			if (usePipe) {
				PipedOutputStream pos = new PipedOutputStream();
				PipedInputStream pis = new PipedInputStream(pos, IOUtils.MTU);
				if (async) {
					CountDownLatch countDownLatch = new CountDownLatch(1);
					CompletableFuture<HttpResponse<InputStream>> completableFuture = sendAsync(context, message, HttpRequest.BodyPublishers.ofInputStream(() -> {
						countDownLatch.countDown();
						return pis;
					}), timeout);
					if (!countDownLatch.await(_httpEndpoint.getConnectionTimeout(), TimeUnit.MILLISECONDS)) {
						pos.close();
						// The future should now deliver an ConnectException
						executionContext.setResource2(completableFuture);
						return executionContext;
					}
					createAsyncHandler(completableFuture, timeout, context, message, workerPool);
				} else {
					Future<HttpResponse<InputStream>> future = workerPool.getExecutorService().submit(() -> send(context, message, HttpRequest.BodyPublishers.ofInputStream(() -> pis), timeout));
					executionContext.setResource2(future);
				}
				if (inPipeline) {
					message.reset(BodyType.OUTPUT_STREAM, pos);
				} else {
					message.writeRawTo(pos, context);
				}
			} else {
				HttpRequest.BodyPublisher bodyPublisher;
				if (contentLength != null && contentLength == 0) {
					bodyPublisher = HttpRequest.BodyPublishers.noBody();
				} else if (message.getBodyType() == BodyType.INPUT_STREAM && (contentLength == null || contentLength > IOUtils.MTU)) {
					InputStream is = message.getBodyAsInputStream(context);
					bodyPublisher = HttpRequest.BodyPublishers.ofInputStream(() -> is);
				} else {
					bodyPublisher = HttpRequest.BodyPublishers.ofByteArray(message.getBodyAsByteArray(context));
				}
				if (async) {
					sendAsync(context, message, bodyPublisher, timeout);
				} else {
					HttpResponse<InputStream> response = send(context, message, bodyPublisher, timeout);
					executionContext.setResource3(response);
				}
			}
			return executionContext;
		}
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		message.closeBody();
		Boolean async = execContext.getResource();
		Future<HttpResponse<InputStream>> future;
		if (MimeHelper.isMimeMultipart(_multipartSubtype, message)) {
			int timeout = message.getTimeleft(_readTimeout).intValue();
			ByteArrayOutputStream bos = execContext.getResource2();
			MimeMultipart mmp = MimeHelper.createMimeMultipart(context, message, _multipartSubtype, _multipartOption, bos);
			message.putHeader(HTTP_HEADER_CONTENT_TYPE, unfoldHttpHeader(mmp.getContentType()));
			WorkerPool workerPool = context.getGlobalContext().getWorkerPool(_workerPool);
			PipedOutputStream pos = new PipedOutputStream();
			PipedInputStream pis = new PipedInputStream(pos, IOUtils.MTU);
			if (async) {
				CountDownLatch countDownLatch = new CountDownLatch(1);
				CompletableFuture<HttpResponse<InputStream>> completableFuture = sendAsync(context, message, HttpRequest.BodyPublishers.ofInputStream(() -> {
					countDownLatch.countDown();
					return pis;
				}), timeout);
				if (countDownLatch.await(_httpEndpoint.getConnectionTimeout(), TimeUnit.MILLISECONDS)) {
					createAsyncHandler(completableFuture, timeout, context, message, workerPool);
					mmp.writeTo(pos);
					future = null;
				} else {
					pos.close();
					future = completableFuture;
				}
			} else {
				future = workerPool.getExecutorService().submit(() -> send(context, message, HttpRequest.BodyPublishers.ofInputStream(() -> pis), timeout));
				mmp.writeTo(pos);
			}
		} else {
			future = execContext.getResource2();
		}
		HttpResponse<InputStream> httpResponse = execContext.getResource3();
		if (future != null) {
			try {
				httpResponse = future.get();
			} catch (java.util.concurrent.ExecutionException e) {
				throw ReflectionUtils.convert(e.getCause(), Exception.class);
			}
		}
		message.clearHeaders();
		if (httpResponse != null) {
			fillESBMessage(message, httpResponse);
		} else {
			message.reset(BodyType.INVALID, null);
		}
	}

	@Override
	protected Action nextAction(ExecutionContext execContext) {
		Boolean async = execContext.getResource();
		return async ? null : super.nextAction(execContext);
	}

}
