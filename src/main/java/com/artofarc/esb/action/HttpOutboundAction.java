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
package com.artofarc.esb.action;

import javax.mail.internet.MimeMultipart;
import javax.xml.bind.DatatypeConverter;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import static com.artofarc.esb.http.HttpConstants.*;

import com.artofarc.esb.http.HttpEndpoint;
import com.artofarc.esb.http.Http1UrlSelector;
import com.artofarc.esb.http.Http1UrlSelector.HttpUrlConnection;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.MimeHelper;
import com.artofarc.util.ByteArrayOutputStream;
import com.artofarc.util.URLUtils;

import static com.artofarc.esb.message.ESBConstants.*;
import com.artofarc.esb.message.ESBMessage;

public class HttpOutboundAction extends Action {

	private final HttpEndpoint _httpEndpoint;
	private final Integer _readTimeout;
	private final Integer _chunkLength;
	private final String _multipartSubtype, _multipartOption;

	public HttpOutboundAction(HttpEndpoint httpEndpoint, int readTimeout, Integer chunkLength, String multipartSubtype, String multipartOption) {
		_httpEndpoint = httpEndpoint;
		_readTimeout = readTimeout;
		_chunkLength = chunkLength;
		_multipartSubtype = multipartSubtype;
		_multipartOption = multipartOption;
		_pipelineStop = true;
	}

	private HttpUrlConnection createHttpURLConnection(Context context, ESBMessage message, Long contentLength) throws Exception {
		Http1UrlSelector httpUrlSelector = context.getGlobalContext().getHttpEndpointRegistry().getHttpUrlSelector(_httpEndpoint, context.getGlobalContext().getDefaultWorkerPool());
		String method = message.getVariable(HttpMethod);
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
		if (message.getHeader(HTTP_HEADER_ACCEPT) == null) {
			// https://bugs.openjdk.org/browse/JDK-8163921
			message.putHeader(HTTP_HEADER_ACCEPT, "*/*");
		}
		String basicAuthCredential = _httpEndpoint.getBasicAuthCredential();
		if (basicAuthCredential != null) {
			basicAuthCredential = (String) eval(basicAuthCredential, context, message);
			message.putHeader(HTTP_HEADER_AUTHORIZATION, "Basic " + DatatypeConverter.printBase64Binary(basicAuthCredential.getBytes(ESBMessage.CHARSET_DEFAULT)));
		}
		int timeout = message.getTimeleft(_readTimeout).intValue();
		HttpUrlConnection httpUrlConnection = httpUrlSelector.connectTo(_httpEndpoint, timeout, method, appendHttpUrl, message.getHeaders(), _chunkLength, contentLength);
		context.putResource(HttpURLConnection, httpUrlConnection);
		message.getVariables().put(HttpURLOutbound, httpUrlConnection.getHttpUrl().getUrlStr());
		return httpUrlConnection;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		message.determineSinkContentType();
		if (MimeHelper.isMimeMultipart(_multipartSubtype, message)) {
			if (inPipeline) {
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				message.reset(BodyType.OUTPUT_STREAM, bos);
				return new ExecutionContext(bos);
			}
			return null;
		} else {
			Long contentLength = inPipeline ? null : message.getOutputLength();
			HttpUrlConnection httpUrlConnection = createHttpURLConnection(context, message, contentLength);
			try {
				if (inPipeline) {
					message.reset(BodyType.OUTPUT_STREAM, httpUrlConnection.getOutputStream());
				} else if (!message.isEmpty()) {
					message.writeTo(httpUrlConnection.getOutputStream(), context);
				}
			} catch (Exception e) {
				httpUrlConnection.close();
				context.removeResource(HttpURLConnection);
				throw e;
			}
			return new ExecutionContext(httpUrlConnection); 
		}
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		message.closeBody();
		if (MimeHelper.isMimeMultipart(_multipartSubtype, message)) {
			ByteArrayOutputStream bos = execContext != null ? execContext.getResource() : null;
			MimeMultipart mmp = MimeHelper.createMimeMultipart(context, message, _multipartSubtype, _multipartOption, bos);
			message.putHeader(HTTP_HEADER_CONTENT_TYPE, unfoldHttpHeader(mmp.getContentType()));
			HttpUrlConnection httpUrlConnection = createHttpURLConnection(context, message, null);
			mmp.writeTo(httpUrlConnection.getOutputStream());
		}
	}

	@Override
	protected void close(Context context, ExecutionContext execContext, boolean exception) {
		if (exception) {
			HttpUrlConnection httpUrlConnection = context.removeResource(HttpURLConnection);
			httpUrlConnection.close();
		}
	}

}
