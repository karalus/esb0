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
import java.net.HttpURLConnection;
import java.net.MalformedURLException;

import javax.xml.transform.TransformerException;
import javax.xml.xquery.XQException;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.http.HttpEndpoint;
import com.artofarc.esb.http.HttpUrlSelector;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.ESBVariableConstants;

public class HttpOutboundAction extends Action {

	private final HttpEndpoint _httpEndpoint;
	private final int _readTimeout;
	private final Integer _chunkLength;

	public HttpOutboundAction(HttpEndpoint httpEndpoint, int readTimeout, Integer chunkLength) {
		_httpEndpoint = httpEndpoint;
		_readTimeout = readTimeout;
		_chunkLength = chunkLength;
		_pipelineStop = true;
	}

	public HttpOutboundAction(String url) throws MalformedURLException {
		this(new HttpEndpoint(1000, 0, null, null, System.currentTimeMillis()).addUrl(url, 1, true), 60000, null);
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws IOException, TransformerException, XQException {
		HttpUrlSelector httpUrlSelector = context.getPoolContext().getGlobalContext().getHttpEndpointRegistry().getHttpUrlSelector(_httpEndpoint);
		String method = message.getVariable(ESBVariableConstants.HttpMethod);
		// for REST append to URL
		String appendHttpUrlPath = message.getVariable(ESBVariableConstants.appendHttpUrlPath);
		HttpURLConnection conn = httpUrlSelector.connectTo(_httpEndpoint, method, appendHttpUrlPath, message.getHeaders().entrySet(), true, _chunkLength);
		conn.setReadTimeout(_readTimeout);
		if (inPipeline) {
			message.reset(BodyType.OUTPUT_STREAM, conn.getOutputStream());
		} else {
			message.writeTo(conn.getOutputStream(), context);
		}
		return new ExecutionContext(conn);
	}

	@Override
	protected void execute(Context context, ExecutionContext resource, ESBMessage message, boolean nextActionIsPipelineStop) throws IOException {
		HttpURLConnection conn = resource.getResource();
		conn.getOutputStream().close();
		message.reset(BodyType.INVALID, null);
		message.getVariables().put(ESBVariableConstants.HttpURLConnection, conn);
	}

}
