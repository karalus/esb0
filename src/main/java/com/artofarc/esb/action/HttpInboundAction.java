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

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map.Entry;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.http.HttpUrlSelector.HttpUrlConnectionWrapper;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.MimeHelper;

public class HttpInboundAction extends Action {

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		HttpUrlConnectionWrapper wrapper = message.removeVariable(ESBConstants.HttpURLConnection);
		HttpURLConnection conn = wrapper.getHttpURLConnection();
		message.getVariables().put(ESBConstants.HttpResponseCode, conn.getResponseCode());
		message.clearHeaders();
		for (Entry<String, List<String>> entry : conn.getHeaderFields().entrySet()) {
			if (entry.getKey() != null) {
				Object value = entry.getValue();
				if (entry.getValue().size() == 1) {
					value = entry.getValue().get(0);
				}
				message.putHeader(entry.getKey(), value);
			}
		}
		String contentType = message.getHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE);
		if (contentType != null) {
			message.setCharset(HttpConstants.getValueFromHttpHeader(contentType, HttpConstants.HTTP_HEADER_CONTENT_TYPE_PARAMETER_CHARSET));
		}
		InputStream inputStream = conn.getResponseCode() < HttpURLConnection.HTTP_BAD_REQUEST ? conn.getInputStream() : conn.getErrorStream();
		if (inputStream == null) {
			inputStream = new java.io.ByteArrayInputStream(new byte[0]);
		} 
		message.reset(BodyType.INPUT_STREAM, inputStream);
		if (MimeHelper.parseMultipart(message, contentType)) {
			inputStream.close();
			inputStream = message.getBody();
		}
		return new ExecutionContext(inputStream, wrapper);
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		if (nextActionIsPipelineStop) {
			InputStream inputStream = execContext.getResource();
			if (message.isSink()) {
				message.copyFrom(inputStream);
			} else {
				message.reset(BodyType.INPUT_STREAM, inputStream);
				message.getBodyAsByteArray(context);
			}
		}
	}

	@Override
	protected void close(ExecutionContext execContext) throws Exception {
		try {
			execContext.<InputStream> getResource().close();
		} finally {
			execContext.<HttpUrlConnectionWrapper> getResource2().close();
		}
	}

}
