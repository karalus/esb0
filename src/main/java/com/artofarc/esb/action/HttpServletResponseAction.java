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

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.util.Map.Entry;
import java.util.StringTokenizer;

import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMultipart;
import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletResponse;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import static com.artofarc.esb.http.HttpConstants.*;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.servlet.GenericHttpListener;

public class HttpServletResponseAction extends Action {
	
	private final boolean _supportCompression, _multipartResponse;

	public HttpServletResponseAction(boolean supportCompression, boolean multipartResponse) {
		_pipelineStop = true;
		_supportCompression = supportCompression;
		_multipartResponse = multipartResponse;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		AsyncContext asyncContext = message.removeVariable(ESBConstants.AsyncContext);
		if (asyncContext == null) {
			throw new ExecutionException(this, ESBConstants.AsyncContext + " not set");
		}
		HttpServletResponse response = (HttpServletResponse) asyncContext.getResponse();
		ExecutionContext executionContext = new ExecutionContext(asyncContext);
		if (message.getBodyType() == BodyType.EXCEPTION) {
			GenericHttpListener.sendErrorResponse(response, message.<Exception> getBody());
		} else if (message.getVariable(ESBConstants.redirect) != null) {
			response.sendRedirect(message.<String> getVariable(ESBConstants.redirect));
//			response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
//			response.setHeader("Location", message.<String> getVariable(ESBVariableConstants.redirect));
		} else {
			Number httpResponseCode = message.getVariable(ESBConstants.HttpResponseCode);
			if (httpResponseCode == null) {
				Boolean hasFault = message.getVariable(ESBConstants.hasFault);
				httpResponseCode = hasFault != null && hasFault ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : HttpServletResponse.SC_OK;
			}
			response.setStatus(httpResponseCode.intValue());
			final String acceptCharset = message.getVariable(HTTP_HEADER_ACCEPT_CHARSET);
			if (acceptCharset != null) {
				message.setSinkEncoding(getValueFromHttpHeader(acceptCharset, ""));
			}
			if (_supportCompression) checkCompression(message);
			checkFastInfoSet(message);
			if (_multipartResponse) {
				if (inPipeline) {
					ByteArrayOutputStream bos = new ByteArrayOutputStream(ESBMessage.MTU);
					message.reset(BodyType.OUTPUT_STREAM, bos);
					executionContext.setResource2(bos);
				}
			} else {
				response.setCharacterEncoding(message.getSinkEncoding());
				for (Entry<String, Object> entry : message.getHeaders().entrySet()) {
					response.setHeader(entry.getKey(), entry.getValue().toString());
				}
				if (inPipeline) {
					message.reset(BodyType.OUTPUT_STREAM, response.getOutputStream());
				} else if (message.getBodyType() != BodyType.INVALID) {
					message.writeTo(response.getOutputStream(), context);
				}
				if (message.getAttachments().size() > 0) {
					logger.warning("Message has attachments");
				}
			}
		}
		return executionContext;
	}

	private static void checkCompression(ESBMessage message) {
		final String acceptEncoding = message.getVariable(HTTP_HEADER_ACCEPT_ENCODING);
		if (acceptEncoding != null) {
			final StringTokenizer tokenizer = new StringTokenizer(acceptEncoding, ", ");
			while (tokenizer.hasMoreTokens()) {
				final String contentEncoding = tokenizer.nextToken();
				if (contentEncoding.equals("gzip") || contentEncoding.equals("deflate")) {
					message.putHeader(HTTP_HEADER_CONTENT_ENCODING, contentEncoding);
					break;
				}
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
		if (message.getBodyType() == BodyType.OUTPUT_STREAM || message.getBodyType() == BodyType.WRITER) {
			// necessary for filter streams
			message.<Closeable> getBody().close();
		}
		if (_multipartResponse) {
			String contentType = message.<String> getHeader(HTTP_HEADER_CONTENT_TYPE);
			MimeMultipart mmp = new MimeMultipart("related; " + HTTP_HEADER_CONTENT_TYPE_PARAMETER_TYPE + '"' + contentType + '"');
			ByteArrayOutputStream bos = execContext.getResource2();
	   	byte[] content = bos != null ? bos.toByteArray() : message.getBodyAsByteArray(context);
			InternetHeaders headers = new InternetHeaders();
			message.putHeader(HTTP_HEADER_CONTENT_TYPE, contentType + "; " + HTTP_HEADER_CONTENT_TYPE_PARAMETER_CHARSET + message.getSinkEncoding());
			for (Entry<String, Object> entry : message.getHeaders().entrySet()) {
				headers.setHeader(entry.getKey(), entry.getValue().toString());
			}
			MimeBodyPart part = new MimeBodyPart(headers, content);
			mmp.addBodyPart(part);
			for (MimeBodyPart bodyPart : message.getAttachments().values()) {
				mmp.addBodyPart(bodyPart);
			}
			HttpServletResponse response = (HttpServletResponse) asyncContext.getResponse();
			response.setContentType(mmp.getContentType());
			mmp.writeTo(response.getOutputStream());
		}
		asyncContext.complete();
	}

}
