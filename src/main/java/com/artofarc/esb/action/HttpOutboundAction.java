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

import java.net.HttpURLConnection;
import java.util.Map.Entry;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import static com.artofarc.esb.http.HttpConstants.*;
import com.artofarc.esb.http.HttpEndpoint;
import com.artofarc.esb.http.HttpUrlSelector;
import com.artofarc.esb.message.BodyType;
import static com.artofarc.esb.message.ESBConstants.*;
import com.artofarc.esb.message.ESBMessage;

public class HttpOutboundAction extends Action {

	private final HttpEndpoint _httpEndpoint;
	private final Integer _readTimeout;
	private final Integer _chunkLength;

	public HttpOutboundAction(HttpEndpoint httpEndpoint, int readTimeout, Integer chunkLength) {
		_httpEndpoint = httpEndpoint;
		_readTimeout = readTimeout;
		_chunkLength = chunkLength;
		_pipelineStop = true;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		HttpUrlSelector httpUrlSelector = context.getPoolContext().getGlobalContext().getHttpEndpointRegistry().getHttpUrlSelector(_httpEndpoint);
		String method = message.getVariable(HttpMethod);
		// for REST append to URL
		String appendHttpUrl = message.getVariable(appendHttpUrlPath);
		String queryString = message.getVariable(QueryString);
		if (queryString != null && queryString.length() > 0) {
			appendHttpUrl += "?" + queryString;
		}
		Entry<String, String> contentType = message.getHeaderEntry(HTTP_HEADER_CONTENT_TYPE);
		if (contentType != null) {
			contentType.setValue(contentType.getValue() + ';' + HTTP_HEADER_CONTENT_TYPE_PARAMETER_CHARSET + message.getSinkEncoding());
		}
		HttpUrlSelector.HttpUrlConnectionWrapper wrapper = httpUrlSelector.connectTo(_httpEndpoint, method, appendHttpUrl, message.getHeaders().entrySet(), _chunkLength);
		HttpURLConnection conn = wrapper.getHttpURLConnection();  
		message.getVariables().put(HttpURLOutbound, conn.getURL().toString());
		conn.setReadTimeout(message.getTimeleft(_readTimeout).intValue());
		if (inPipeline) {
			message.reset(BodyType.OUTPUT_STREAM, conn.getOutputStream());
		} else {
			if (!message.isEmpty()) {
				message.writeTo(conn.getOutputStream(), context);
			}
		}
		return new ExecutionContext(wrapper);
	}

	@Override
	protected void execute(Context context, ExecutionContext resource, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		message.closeBody();
		message.getVariables().put(HttpURLConnection, resource.getResource());
	}

}
