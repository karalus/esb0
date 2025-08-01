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
package com.artofarc.esb.servlet;

import java.io.OutputStream;
import java.util.List;
import java.util.Map.Entry;

import javax.mail.internet.MimeMultipart;
import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.artofarc.esb.action.Action;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import static com.artofarc.esb.http.HttpConstants.*;

import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.MimeHelper;
import com.artofarc.util.ByteArrayOutputStream;
import com.artofarc.util.IOUtils;

public class HttpResponseAction extends Action {

	private final static long thresholdCompression = Long.parseLong(System.getProperty("esb0.http.thresholdCompression", "1000"));

	private final boolean _supportCompression;
	private final String _multipartSubtype, _multipartOption;
	private final Integer _bufferSize;

	public HttpResponseAction(boolean supportCompression, String multipartSubtype, String multipartOption, Integer bufferSize) {
		_pipelineStop = true;
		_offeringSink = true;
		_supportCompression = supportCompression;
		_multipartSubtype = multipartSubtype;
		_multipartOption = multipartOption;
		_bufferSize = bufferSize;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		AsyncContext asyncContext = message.removeVariable(ESBConstants.AsyncContext);
		HttpServletResponse response = (HttpServletResponse) asyncContext.getResponse();
		ExecutionContext executionContext = new ExecutionContext(asyncContext);
		String redirect = message.getVariable(ESBConstants.redirect);
		boolean mimeMultipart;
		if (message.getBodyType() == BodyType.EXCEPTION) {
			if (context.isTransacted()) {
				throw message.<Exception> getBody();
			} else {
				GenericHttpListener.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, message.<Exception> getBody());
			}
		} else if (redirect != null && !redirect.isEmpty()) {
			response.sendRedirect(redirect);
		} else if (checkContentType(message, response, mimeMultipart = MimeHelper.isMimeMultipart(message, _multipartSubtype, _multipartOption))) {
			executionContext.setResource3(mimeMultipart);
			if (_bufferSize != null) response.setBufferSize(_bufferSize);
			Number httpResponseCode = message.getVariable(ESBConstants.HttpResponseCode);
			if (httpResponseCode != null) {
				response.setStatus(httpResponseCode.intValue());
			}
			message.removeHeader(HTTP_HEADER_TRANSFER_ENCODING);
			if (_supportCompression) checkCompression(message, inPipeline);
			if (mimeMultipart) {
				if (inPipeline) {
					ByteArrayOutputStream bos = new ByteArrayOutputStream();
					message.reset(BodyType.OUTPUT_STREAM, bos);
					executionContext.setResource2(bos);
				}
			} else {
				for (Entry<String, Object> entry : message.getHeaders()) {
					if (entry.getValue() instanceof String) {
						response.setHeader(entry.getKey(), (String) entry.getValue());
					} else if (entry.getValue() instanceof Integer) {
						response.setIntHeader(entry.getKey(), (Integer) entry.getValue());
					} else if (entry.getValue() instanceof Long) {
						response.setDateHeader(entry.getKey(), (Long) entry.getValue());
					} else if (entry.getValue() instanceof List) {
						@SuppressWarnings("unchecked")
						List<String> values = (List<String>) entry.getValue();
						for (String value : values) {
							response.addHeader(entry.getKey(), value);
						}
					}
				}
				boolean doOutput = doOutput((HttpServletRequest) asyncContext.getRequest(), httpResponseCode);
				// prevent flushing to avoid "transfer encoding chunked" on small responses
				if (inPipeline) {
					message.reset(BodyType.OUTPUT_STREAM, doOutput ? message.getCompressedOutputStream(new IOUtils.PreventFlushOutputStream(response.getOutputStream()), true) : OutputStream.nullOutputStream());
				} else if (message.getBodyType() != BodyType.INVALID) {
					Long contentLength = message.getLengthExact();
					if (contentLength == null) {
						if (doOutput) {
							message.writeTo(new IOUtils.PreventFlushOutputStream(response.getOutputStream()), context);
						}
					} else if (contentLength > 0) {
						response.setContentLengthLong(contentLength);
						if (doOutput) {
							message.writeTo(response.getOutputStream(), context);
						}
					}
				}
				if (message.getAttachments().size() > 0) {
					logger.warn("Message has attachments");
				}
			}
		}
		return executionContext;
	}

	private static boolean doOutput(HttpServletRequest request, Number httpResponseCode) {
		// https://stackoverflow.com/questions/16339198/which-http-methods-require-a-body
		return (httpResponseCode == null || httpResponseCode.intValue() != HttpServletResponse.SC_NO_CONTENT && httpResponseCode.intValue() != HttpServletResponse.SC_NOT_MODIFIED) && !"HEAD".equals(request.getMethod());
	}

	private boolean checkContentType(ESBMessage message, HttpServletResponse response, boolean mimeMultipart) throws Exception {
		final String accept = message.getVariable(HTTP_HEADER_ACCEPT);
		final String contentType = message.getHeader(HTTP_HEADER_CONTENT_TYPE);
		String contentTypeInner = contentType != null ? contentType : message.getContentType();
		if (contentTypeInner != null && accept != null) {
			// try upgrade to FastInfoset
			if (accept.contains(HTTP_HEADER_CONTENT_TYPE_FI_SOAP11)) {
				contentTypeInner = HTTP_HEADER_CONTENT_TYPE_FI_SOAP11;
			} else if (accept.contains(HTTP_HEADER_CONTENT_TYPE_FI_SOAP12) && !contentTypeInner.startsWith(HTTP_HEADER_CONTENT_TYPE_FI_SOAP12)) {
				contentTypeInner = HTTP_HEADER_CONTENT_TYPE_FI_SOAP12;
				String soapAction = getValueFromHttpHeader(contentTypeInner, HTTP_HEADER_CONTENT_TYPE_PARAMETER_ACTION);
				if (soapAction != null) {
					contentTypeInner += ';' + HTTP_HEADER_CONTENT_TYPE_PARAMETER_ACTION + '"' + soapAction + '"';
				}
			}
		}
		final String contentTypeOuter = mimeMultipart ? MEDIATYPE_MULTIPART + _multipartSubtype : parseContentType(contentTypeInner);
		if (contentTypeOuter != null && accept != null && !isAcceptable(accept, contentTypeOuter)) {
			GenericHttpListener.sendError(response, HttpServletResponse.SC_NOT_ACCEPTABLE, contentTypeOuter + " does not match " + accept);
			return false;
		}
		if (needsCharset(contentTypeInner)) {
			String acceptCharset = message.getVariable(HTTP_HEADER_ACCEPT_CHARSET);
			if (acceptCharset != null) {
				message.setSinkEncoding(getBestQualityValue(acceptCharset));
			}
			contentTypeInner += "; " + HTTP_HEADER_CONTENT_TYPE_PARAMETER_CHARSET + message.getSinkEncoding();
		}
		if (contentType != contentTypeInner) {
			message.putHeader(HTTP_HEADER_CONTENT_TYPE, contentTypeInner);
		}
		return true;
	}

	private static void checkCompression(ESBMessage message, boolean inPipeline) throws Exception {
		message.addHeader(HTTP_HEADER_VARY, HTTP_HEADER_ACCEPT_ENCODING);
		final String acceptEncoding = message.getVariable(HTTP_HEADER_ACCEPT_ENCODING);
		if (acceptEncoding != null) {
			Long length = message.getLength();
			if (message.getContentEncoding() != null && isAcceptable(acceptEncoding, message.getContentEncoding())) {
				message.putHeader(HTTP_HEADER_CONTENT_ENCODING, message.getContentEncoding());
			} else if (inPipeline || length == null && !message.isEmpty() || length != null && length > thresholdCompression) {
				if (isAcceptable(acceptEncoding, "gzip")) {
					message.putHeader(HTTP_HEADER_CONTENT_ENCODING, "gzip");
				} else if (isAcceptable(acceptEncoding, "deflate")) {
					message.putHeader(HTTP_HEADER_CONTENT_ENCODING, "deflate");
				}
			}
		}
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		AsyncContext asyncContext = execContext.getResource();
		message.closeBody();
		if (Boolean.TRUE.equals(execContext.getResource3())) {
			ByteArrayOutputStream bos = execContext.getResource2();
			MimeMultipart mmp = MimeHelper.createMimeMultipart(context, message, _multipartSubtype, _multipartOption, bos, true);
			HttpServletResponse response = (HttpServletResponse) asyncContext.getResponse();
			response.setContentType(unfoldHttpHeader(mmp.getContentType()));
			mmp.writeTo(response.getOutputStream());
		}
		asyncContext.complete();
	}

}
