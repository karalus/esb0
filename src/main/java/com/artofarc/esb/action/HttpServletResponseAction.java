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

import java.util.Map.Entry;

import javax.mail.internet.MimeMultipart;
import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletResponse;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import static com.artofarc.esb.http.HttpConstants.*;

import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.MimeHelper;
import com.artofarc.esb.servlet.GenericHttpListener;
import com.artofarc.util.ByteArrayOutputStream;
import com.artofarc.util.IOUtils;

public class HttpServletResponseAction extends Action {

	private final boolean _supportCompression;
	private final String _multipartResponse;
	private final Integer _bufferSize;

	public HttpServletResponseAction(boolean supportCompression, String multipartResponse, Integer bufferSize) {
		_pipelineStop = true;
		_supportCompression = supportCompression;
		_multipartResponse = multipartResponse;
		_bufferSize = bufferSize;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		AsyncContext asyncContext = message.removeVariable(ESBConstants.AsyncContext);
		if (asyncContext == null) {
			throw new ExecutionException(this, ESBConstants.AsyncContext + " not set");
		}
		HttpServletResponse response = (HttpServletResponse) asyncContext.getResponse();
		ExecutionContext executionContext = new ExecutionContext(asyncContext);
		String redirect = message.getVariable(ESBConstants.redirect);
		if (message.getBodyType() == BodyType.EXCEPTION) {
			GenericHttpListener.sendError(response, message.<Exception> getBody());
		} else if (redirect != null && !redirect.isEmpty()) {
			response.sendRedirect(redirect);
		} else {
			if (_bufferSize != null) response.setBufferSize(_bufferSize);
			Number httpResponseCode = message.getVariable(ESBConstants.HttpResponseCode);
			if (httpResponseCode != null) {
				response.setStatus(httpResponseCode.intValue());
			}
			String acceptCharset = message.getVariable(HTTP_HEADER_ACCEPT_CHARSET);
			if (acceptCharset != null) {
				message.setSinkEncoding(getValueFromHttpHeader(acceptCharset));
			}
			message.removeHeader(HTTP_HEADER_TRANSFER_ENCODING);
			if (_supportCompression) checkCompression(message);
			checkFastInfoSet(message);
			if (_multipartResponse != null) {
				if (inPipeline) {
					ByteArrayOutputStream bos = new ByteArrayOutputStream();
					message.reset(BodyType.OUTPUT_STREAM, bos);
					executionContext.setResource2(bos);
				}
			} else {
				response.setCharacterEncoding(message.getSinkEncoding());
				for (Entry<String, Object> entry : message.getHeaders()) {
					if (entry.getValue() instanceof String) {
						response.setHeader(entry.getKey(), (String) entry.getValue());
					} else if (entry.getValue() instanceof Integer) {
						response.setIntHeader(entry.getKey(), (Integer) entry.getValue());
					} else if (entry.getValue() instanceof Long) {
						response.setDateHeader(entry.getKey(), (Long) entry.getValue());
					}
				}
				// prevent flushing to avoid "transfer encoding chunked" on small responses
				if (inPipeline) {
					message.reset(BodyType.OUTPUT_STREAM, new IOUtils.PreventFlushOutputStream(response.getOutputStream()));
				} else if (message.getBodyType() != BodyType.INVALID) {
					message.writeTo(new IOUtils.PreventFlushOutputStream(response.getOutputStream()), context);
				}
				if (message.getAttachments().size() > 0) {
					logger.warn("Message has attachments");
				}
			}
		}
		return executionContext;
	}

	private static void checkCompression(ESBMessage message) {
		final String acceptEncoding = message.getVariable(HTTP_HEADER_ACCEPT_ENCODING);
		if (acceptEncoding != null) {
			if (acceptEncoding.contains("gzip")) {
				message.putHeader(HTTP_HEADER_CONTENT_ENCODING, "gzip");
			} else if (acceptEncoding.contains("deflate")) {
				message.putHeader(HTTP_HEADER_CONTENT_ENCODING, "deflate");
			}
		}
	}

	private static void checkFastInfoSet(ESBMessage message) {
		final String accept = message.getVariable(HTTP_HEADER_ACCEPT);
		if (accept != null) {
			if (accept.contains(HTTP_HEADER_CONTENT_TYPE_FI_SOAP11)) {
				message.putHeader(HTTP_HEADER_CONTENT_TYPE, HTTP_HEADER_CONTENT_TYPE_FI_SOAP11);
			} else if (accept.contains(HTTP_HEADER_CONTENT_TYPE_FI_SOAP12)) {
				message.putHeader(HTTP_HEADER_CONTENT_TYPE, HTTP_HEADER_CONTENT_TYPE_FI_SOAP12);
			}
		}
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		AsyncContext asyncContext = execContext.getResource();
		message.closeBody();
		if (_multipartResponse != null) {
			ByteArrayOutputStream bos = execContext.getResource2();
			MimeMultipart mmp = MimeHelper.createMimeMultipart(context, message, _multipartResponse, bos);
			HttpServletResponse response = (HttpServletResponse) asyncContext.getResponse();
			response.setContentType(mmp.getContentType());
			mmp.writeTo(response.getOutputStream());
		}
		asyncContext.complete();
	}

}
