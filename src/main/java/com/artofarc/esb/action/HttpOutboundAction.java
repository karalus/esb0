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
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;

import javax.xml.transform.TransformerException;
import javax.xml.xquery.XQException;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.ESBVariableConstants;

public class HttpOutboundAction extends Action {

	public static final String HTTP_HEADER_CONTENT_TYPE = "Content-Type";
	public static final String HTTP_HEADER_ACCEPT = "Accept";
	public static final String HTTP_HEADER_SOAP_ACTION = "SOAPAction";

	private final int _connectionTimeout;
	private final int _readTimeout;
	private final Integer _chunkLength;
	private final String[] _urls;

	public HttpOutboundAction(int connectionTimeout, int readTimeout, Integer chunkLength, List<String> urls) throws MalformedURLException {
		for (String url : urls) {
			new URL(url);
		}
		_urls = new String[urls.size()];
		urls.toArray(_urls);
		_connectionTimeout = connectionTimeout;
		_readTimeout = readTimeout;
		_chunkLength = chunkLength;
		_pipelineStop = true;
	}

	public HttpOutboundAction(String url) throws MalformedURLException {
		this(1000, 60000, null, Arrays.asList(url));
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws IOException, TransformerException, XQException {
		// random loadbalancer
		int i = (int) Math.random() * _urls.length;
		String url = _urls[i] + message.getVariable(ESBVariableConstants.appendHttpUrlPath, "");
		HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
		conn.setConnectTimeout(_connectionTimeout);
		conn.setReadTimeout(_readTimeout);
		conn.setRequestMethod(message.<String> getVariable(ESBVariableConstants.HttpMethod));
		conn.setDoOutput(true);
		if (_chunkLength != null) {
			conn.setChunkedStreamingMode(_chunkLength);
		}
		for (Entry<String, Object> entry : message.getHeaders().entrySet()) {
			conn.addRequestProperty(entry.getKey(), entry.getValue().toString());
		}
		conn.connect();
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
