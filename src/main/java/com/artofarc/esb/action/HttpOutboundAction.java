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
package com.artofarc.esb.action;

import javax.mail.internet.MimeMultipart;
import javax.xml.bind.DatatypeConverter;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import static com.artofarc.esb.http.HttpConstants.*;

import com.artofarc.esb.http.HttpEndpoint;
import com.artofarc.esb.http.HttpUrlSelector;
import com.artofarc.esb.http.HttpUrlSelector.HttpUrlConnectionWrapper;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.MimeHelper;
import com.artofarc.util.ByteArrayOutputStream;

import static com.artofarc.esb.message.ESBConstants.*;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.HttpQueryHelper;

public class HttpOutboundAction extends Action {

	private final HttpEndpoint _httpEndpoint;
	private final Integer _readTimeout;
	private final Integer _chunkLength;
	private final String _multipartRequest;

	public HttpOutboundAction(HttpEndpoint httpEndpoint, int readTimeout, Integer chunkLength, String multipartRequest) {
		_httpEndpoint = httpEndpoint;
		_readTimeout = readTimeout;
		_chunkLength = chunkLength;
		_multipartRequest = multipartRequest;
		_pipelineStop = true;
	}

	private HttpUrlSelector.HttpUrlConnectionWrapper createHttpURLConnection(Context context, ESBMessage message, String contentType, Long contentLength) throws Exception {
		HttpUrlSelector httpUrlSelector = context.getGlobalContext().getHttpEndpointRegistry().getHttpUrlSelector(_httpEndpoint);
		String method = message.getVariable(HttpMethod);
		// for REST append to URL
		String appendHttpUrl = message.getVariable(appendHttpUrlPath);
		String queryString = HttpQueryHelper.getQueryString(message);
		if (queryString != null) {
			appendHttpUrl += "?" + queryString;
		}
		if (contentType != null) {
			message.putHeader(HTTP_HEADER_CONTENT_TYPE, contentType);
		}
		String basicAuthCredential = _httpEndpoint.getBasicAuthCredential();
		if (basicAuthCredential != null) {
			basicAuthCredential = (String) bindVariable(basicAuthCredential, context, message);
			message.putHeader(HTTP_HEADER_AUTHORIZATION, "Basic " + DatatypeConverter.printBase64Binary(basicAuthCredential.getBytes(ESBMessage.CHARSET_DEFAULT)));
		}
		int timeout = message.getTimeleft(_readTimeout).intValue();
		HttpUrlConnectionWrapper wrapper = httpUrlSelector.connectTo(_httpEndpoint, timeout, method, appendHttpUrl, message.getHeaders(), _chunkLength, contentLength);
		message.getVariables().put(HttpURLConnection, wrapper);
		message.getVariables().put(HttpURLOutbound, wrapper.getHttpUrl().getUrlStr());
		return wrapper;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		if (_multipartRequest != null) {
			if (inPipeline) {
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				message.reset(BodyType.OUTPUT_STREAM, bos);
				return new ExecutionContext(bos); 
			}
			return null;
		} else {
			String contentType = message.getHeader(HTTP_HEADER_CONTENT_TYPE);
			if (contentType != null) {
				contentType += ';' + HTTP_HEADER_CONTENT_TYPE_PARAMETER_CHARSET + message.getSinkEncoding();
			}
			Long contentLength = inPipeline ? null : message.getByteLength();
			HttpUrlConnectionWrapper wrapper = createHttpURLConnection(context, message, contentType, contentLength);
			try {
				if (inPipeline) {
					message.reset(BodyType.OUTPUT_STREAM, wrapper.getHttpURLConnection().getOutputStream());
				} else {
					if (!message.isEmpty()) {
						message.writeTo(wrapper.getHttpURLConnection().getOutputStream(), context);
					}
				}
			} catch (Exception e) {
				wrapper.close();
				throw e;
			}
			return new ExecutionContext(wrapper); 
		}
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		message.closeBody();
		if (_multipartRequest != null) {
			ByteArrayOutputStream bos = execContext.getResource();
			MimeMultipart mmp = MimeHelper.createMimeMultipart(context, message, _multipartRequest, bos);
			HttpUrlConnectionWrapper wrapper = createHttpURLConnection(context, message, mmp.getContentType(), null);
			mmp.writeTo(wrapper.getHttpURLConnection().getOutputStream());
		}
	}

	@Override
	protected void close(ExecutionContext execContext, ESBMessage message, boolean exception) {
		if (exception) {
			HttpUrlConnectionWrapper wrapper = message.removeVariable(HttpURLConnection);
			wrapper.close();
		}
	}

}
