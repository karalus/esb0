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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map.Entry;

import javax.xml.transform.Result;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.ESBVariableConstants;

public class HttpInboundAction extends Action {

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws IOException {
		HttpURLConnection conn = message.removeVariable(ESBVariableConstants.HttpURLConnection);
		message.getVariables().put(ESBVariableConstants.HttpResponseCode, conn.getResponseCode());
		message.getVariables().put(HttpConstants.HTTP_HEADER_ACCEPT_ENCODING, message.getHeader(HttpConstants.HTTP_HEADER_ACCEPT_ENCODING));
		message.getHeaders().clear();
		for (Entry<String, List<String>> entry : conn.getHeaderFields().entrySet()) {
			Object value = entry.getValue();
			if (entry.getValue().size() == 1) {
				value = entry.getValue().get(0);
			}
			message.getHeaders().put(entry.getKey(), value);
		}
		String contentType = message.getHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE);
		message.setCharsetName(HttpConstants.getCharsetFromContentType(contentType));
		message.reset(BodyType.INPUT_STREAM, getInputStream(conn));
		return new ExecutionContext(conn);
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		if (nextActionIsPipelineStop) {
			HttpURLConnection conn = execContext.getResource();
			if (message.getBodyType() == BodyType.OUTPUT_STREAM) {
				OutputStream os = message.getBody();
				ESBMessage.copyStream(getInputStream(conn), os);
			} else {
				// TODO: Code coverage?
				Result result = message.getBodyAsSinkResult();
				message.reset(BodyType.INPUT_STREAM, getInputStream(conn));
				message.writeTo(result, context);
			}
		}
	}

	@Override
	protected void close(ExecutionContext resource) throws Exception {
		HttpURLConnection conn = resource.getResource();
		getInputStream(conn).close();
	}

	public static InputStream getInputStream(HttpURLConnection conn) throws IOException {
		return conn.getResponseCode() < HttpURLConnection.HTTP_BAD_REQUEST ? conn.getInputStream() : conn.getErrorStream();
	}

}
