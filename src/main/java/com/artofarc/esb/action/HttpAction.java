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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import javax.mail.internet.MimeMultipart;
import javax.xml.bind.DatatypeConverter;

import com.artofarc.esb.context.AsyncProcessingPool;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.WorkerPool;
import static com.artofarc.esb.http.HttpConstants.*;
import com.artofarc.esb.http.Http2UrlSelector;
import com.artofarc.esb.http.HttpEndpoint;
import com.artofarc.esb.message.BodyType;
import static com.artofarc.esb.message.ESBConstants.*;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.MimeHelper;
import com.artofarc.util.ByteArrayOutputStream;
import com.artofarc.util.DataStructures;
import com.artofarc.util.IOUtils;
import com.artofarc.util.ReflectionUtils;

public class HttpAction extends Action {

	private final HttpEndpoint _httpEndpoint;
	private final Integer _readTimeout;
	private final String _workerPool;
	private final String _multipartSubtype, _multipartOption;

	public HttpAction(HttpEndpoint httpEndpoint, int readTimeout, String workerPool, String multipartSubtype, String multipartOption) {
		_httpEndpoint = httpEndpoint;
		_readTimeout = readTimeout;
		_workerPool = workerPool;
		_multipartSubtype = multipartSubtype;
		_multipartOption = multipartOption;
		_pipelineStop = true;
	}

	private static void fillESBMessage(Context context, ESBMessage message, HttpResponse<InputStream> httpResponse) throws Exception {
		message.getVariables().put(HttpURLOutbound, httpResponse.uri().toString());
		message.getVariables().put(HttpResponseCode, httpResponse.statusCode());
		for (Map.Entry<String, List<String>> entry : httpResponse.headers().map().entrySet()) {
			// ignore pseudo headers (https://www.rfc-editor.org/rfc/rfc7540#section-8.1.2.1)
			if (entry.getKey().charAt(0) != ':') {
				List<String> values = entry.getValue();
				message.putHeader(entry.getKey(), values.size() > 1 ? values : values.get(0));
			}
		}
		InputStream inputStream = httpResponse.body();
		message.reset(null, inputStream);
		if (message.prepareContent(context)) {
			inputStream.close();
		}
	}

	private HttpRequest.Builder createHttpRequestBuilder(Context context, ESBMessage message, int timeout, HttpRequest.BodyPublisher bodyPublisher) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder().method(message.getVariable(HttpMethod), bodyPublisher).timeout(Duration.ofMillis(timeout));
		for (Map.Entry<String, Object> entry : message.getHeaders()) {
			if (entry.getValue() instanceof List) {
				@SuppressWarnings("unchecked")
				List<String> values = (List<String>) entry.getValue();
				for (String value : values) {
					builder.header(entry.getKey(), value);
				}
			} else {
				builder.header(entry.getKey(), entry.getValue().toString());
			}
		}
		String basicAuthCredential = _httpEndpoint.getBasicAuthCredential();
		if (basicAuthCredential != null) {
			basicAuthCredential = (String) eval(basicAuthCredential, context, message);
			builder.header(HTTP_HEADER_AUTHORIZATION, "Basic " + DatatypeConverter.printBase64Binary(basicAuthCredential.getBytes(ESBMessage.CHARSET_DEFAULT)));
		}
		return builder;
	}

	private String createAppendHttpUrl(ESBMessage message) throws Exception {
		// for REST append to URL
		String appendHttpUrl = message.getVariable(appendHttpUrlPath, "");
		String queryString = message.getVariable(QueryString);
		if (queryString == null || queryString.isEmpty()) {
			String httpQueryParameter = message.getVariable(HttpQueryParameter);
			queryString = httpQueryParameter != null ? createURLEncodedString(null, message, httpQueryParameter) : null;
		}
		if (queryString != null) {
			appendHttpUrl += "?" + queryString;
		}
		return appendHttpUrl;
	}

	private BiFunction<HttpResponse<InputStream>, Throwable, Void> createAsyncHandler(WorkerPool workerPool, Context context, ESBMessage message, int timeout) throws Exception {
		AsyncProcessingPool asyncProcessingPool = workerPool.getAsyncProcessingPool();
		if (asyncProcessingPool == null) {
			throw new ExecutionException(this, "No AsyncProcessingPool in WorkerPool " + workerPool.getName());
		}
		Object correlationID = asyncProcessingPool.saveContext(null, _nextAction, DataStructures.moveToNewList(context.getExecutionStack()), new ArrayList<>(context.getStackErrorHandler()),
				context.getStackPos(), message.getVariables(), _httpEndpoint.getMaxConnectTimeout() + timeout + System.currentTimeMillis());

		return (httpResponse, exception) -> {
			ESBMessage esbMessage = exception == null ? new ESBMessage(BodyType.INVALID, null)
					: new ESBMessage(BodyType.EXCEPTION, exception.getCause() != null ? exception.getCause() : exception);
			Context workerContext = workerPool.getContext();
			Action action = workerPool.getAsyncProcessingPool().restoreContext(correlationID, workerContext, esbMessage);
			try {
				if (httpResponse != null) {
					fillESBMessage(context, esbMessage, httpResponse);
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
		};
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		message.determineSinkContentType();
		boolean async = message.getVariables().containsKey(AsyncContext);
		ExecutionContext executionContext = new ExecutionContext(async);
		if (MimeHelper.isMimeMultipart(_multipartSubtype, message)) {
			if (inPipeline) {
				message.reset(BodyType.OUTPUT_STREAM, new ByteArrayOutputStream());
				executionContext.setResource2(message.getBody());
			}
		} else {
			Long contentLength = inPipeline ? null : message.getLengthExact();
			boolean doOutput = contentLength == null || contentLength > 0;
			int timeout = message.getTimeleft(_readTimeout).intValue();
			WorkerPool workerPool = context.getGlobalContext().getWorkerPool(_workerPool);
			Http2UrlSelector http2UrlSelector = context.getGlobalContext().getHttpEndpointRegistry().getHttpUrlSelector(_httpEndpoint, workerPool);
			CountDownLatch countDownLatch;
			// check, if we need a pipe
			boolean usePipe = inPipeline || (contentLength == null && message.getBodyType() != BodyType.INPUT_STREAM);
			if (usePipe) {
				PipedOutputStream pos = new PipedOutputStream();
				PipedInputStream pis = new PipedInputStream(pos, IOUtils.MTU);
				countDownLatch = new CountDownLatch(1);
				HttpRequest.Builder requestBuilder = createHttpRequestBuilder(context, message, timeout, HttpRequest.BodyPublishers.ofInputStream(() -> {
					countDownLatch.countDown();
					return pis;
				}));
				if (async) {
					http2UrlSelector.sendAsync(_httpEndpoint, requestBuilder, createAppendHttpUrl(message), doOutput, countDownLatch, createAsyncHandler(workerPool, context, message, timeout), workerPool);
				} else {
					Future<HttpResponse<InputStream>> future = workerPool.getExecutorService().submit(() -> http2UrlSelector.send(_httpEndpoint, requestBuilder, createAppendHttpUrl(message), doOutput, countDownLatch));
					executionContext.setResource2(future);
				}
				if (countDownLatch.await(_httpEndpoint.getMaxConnectTimeout(), TimeUnit.MILLISECONDS)) {
					if (inPipeline) {
						message.reset(BodyType.OUTPUT_STREAM, pos);
					} else {
						message.writeRawTo(pos, context);
					}
				} else {
					pos.close();
				}
			} else {
				HttpRequest.BodyPublisher bodyPublisher;
				if (message.getBodyType() == BodyType.INPUT_STREAM && (contentLength == null || contentLength > IOUtils.MTU)) {
					countDownLatch = new CountDownLatch(1);
					InputStream is = message.getBodyAsInputStream(context);
					bodyPublisher = HttpRequest.BodyPublishers.ofInputStream(() -> {
						countDownLatch.countDown();
						return is;
					});
				} else {
					countDownLatch = null;
					if (contentLength != null && contentLength == 0) {
						bodyPublisher = HttpRequest.BodyPublishers.noBody();
					} else {
						bodyPublisher = HttpRequest.BodyPublishers.ofByteArray(message.getBodyAsByteArray(context));
					}
				}
				HttpRequest.Builder requestBuilder = createHttpRequestBuilder(context, message, timeout, bodyPublisher);
				if (async) {
					http2UrlSelector.sendAsync(_httpEndpoint, requestBuilder, createAppendHttpUrl(message), doOutput, countDownLatch, createAsyncHandler(workerPool, context, message, timeout), workerPool);
				} else {
					HttpResponse<InputStream> response = http2UrlSelector.send(_httpEndpoint, requestBuilder, createAppendHttpUrl(message), doOutput, countDownLatch);
					executionContext.setResource3(response);
				}
			}
		}
		return executionContext;
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		message.closeBody();
		Future<HttpResponse<InputStream>> future;
		if (MimeHelper.isMimeMultipart(_multipartSubtype, message)) {
			ByteArrayOutputStream bos = execContext.getResource2();
			MimeMultipart mmp = MimeHelper.createMimeMultipart(context, message, _multipartSubtype, _multipartOption, bos);
			message.putHeader(HTTP_HEADER_CONTENT_TYPE, unfoldHttpHeader(mmp.getContentType()));
			int timeout = message.getTimeleft(_readTimeout).intValue();
			WorkerPool workerPool = context.getGlobalContext().getWorkerPool(_workerPool);
			Http2UrlSelector http2UrlSelector = context.getGlobalContext().getHttpEndpointRegistry().getHttpUrlSelector(_httpEndpoint, workerPool);
			PipedOutputStream pos = new PipedOutputStream();
			PipedInputStream pis = new PipedInputStream(pos, IOUtils.MTU);
			CountDownLatch countDownLatch = new CountDownLatch(1);
			HttpRequest.Builder requestBuilder = createHttpRequestBuilder(context, message, timeout, HttpRequest.BodyPublishers.ofInputStream(() -> {
				countDownLatch.countDown();
				return pis;
			}));
			Boolean async = execContext.getResource();
			if (async) {
				future = null;
				http2UrlSelector.sendAsync(_httpEndpoint, requestBuilder, createAppendHttpUrl(message), true, countDownLatch, createAsyncHandler(workerPool, context, message, timeout), workerPool);
			} else {
				future = workerPool.getExecutorService().submit(() -> http2UrlSelector.send(_httpEndpoint, requestBuilder, createAppendHttpUrl(message), true, countDownLatch));
			}
			if (countDownLatch.await(_httpEndpoint.getMaxConnectTimeout(), TimeUnit.MILLISECONDS)) {
				mmp.writeTo(pos);
			} else {
				pos.close();
			}
		} else {
			future = execContext.getResource2();
		}
		HttpResponse<InputStream> httpResponse;
		if (future != null) {
			try {
				httpResponse = future.get();
			} catch (java.util.concurrent.ExecutionException e) {
				throw ReflectionUtils.convert(e.getCause(), Exception.class);
			}
		} else {
			httpResponse = execContext.getResource3();
		}
		message.clearHeaders();
		if (httpResponse != null) {
			fillESBMessage(context, message, httpResponse);
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
