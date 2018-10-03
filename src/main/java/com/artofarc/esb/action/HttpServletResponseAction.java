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

import java.io.OutputStream;
import java.util.Map.Entry;
import java.util.StringTokenizer;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletResponse;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import static com.artofarc.esb.http.HttpConstants.*;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.ESBVariableConstants;
import com.artofarc.esb.servlet.GenericHttpListener;

public class HttpServletResponseAction extends Action {
	
	private final boolean _supportCompression;

	public HttpServletResponseAction(boolean supportCompression) {
		_pipelineStop = true;
		_supportCompression = supportCompression;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		AsyncContext asyncContext = message.removeVariable(ESBVariableConstants.AsyncContext);
		HttpServletResponse response;
		if (asyncContext != null) {
			response = (HttpServletResponse) asyncContext.getResponse();
		} else {
			throw new ExecutionException(this, ESBVariableConstants.AsyncContext + " not set");
		}
		if (message.getBodyType() == BodyType.EXCEPTION) {
			GenericHttpListener.sendErrorResponse(response, message.<Exception> getBody());
		} else if (message.getVariable(ESBVariableConstants.redirect) != null) {
			response.sendRedirect(message.<String> getVariable(ESBVariableConstants.redirect));
//			response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
//			response.setHeader("Location", message.<String> getVariable(ESBVariableConstants.redirect));
		} else {
			Number httpResponseCode = message.getVariable(ESBVariableConstants.HttpResponseCode);
			if (httpResponseCode == null) {
				Boolean hasFault = message.getVariable(ESBVariableConstants.hasFault);
				httpResponseCode = hasFault != null && hasFault ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : HttpServletResponse.SC_OK;
			}
			response.setStatus(httpResponseCode.intValue());
			if (_supportCompression) checkCompression(message);
			checkFastInfoSet(message);
			for (Entry<String, Object> entry : message.getHeaders().entrySet()) {
				response.addHeader(entry.getKey(), entry.getValue().toString());
			}
			if (inPipeline) {
				message.reset(BodyType.OUTPUT_STREAM, response.getOutputStream());
			} else if (message.getBodyType() != BodyType.INVALID) {
				message.writeTo(response.getOutputStream(), context);
			}
		}
		return new ExecutionContext(asyncContext);
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
		if (message.getBodyType() == BodyType.OUTPUT_STREAM) {
			// necessary for DeflaterOutputStream
			message.<OutputStream> getBody().close();
		}
		if (asyncContext != null) {
			asyncContext.complete();
		}
	}

}
