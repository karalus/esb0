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
package com.artofarc.esb.action;

import java.io.InputStream;
import java.util.List;
import java.util.Map.Entry;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.http.HttpUrlSelector.HttpUrlConnection;
import com.artofarc.esb.message.*;
import com.artofarc.util.ByteArrayOutputStream;

public class HttpInboundAction extends Action {

	public HttpInboundAction() {
		_streamingToSink = true;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		HttpUrlConnection httpUrlConnection = context.removeResource(ESBConstants.HttpURLConnection);
		try {
			message.getVariables().put(ESBConstants.HttpResponseCode, httpUrlConnection.getResponseCode());
			message.clearHeaders();
			for (Entry<String, List<String>> entry : httpUrlConnection.getHeaders().entrySet()) {
				if (entry.getKey() != null) {
					message.putHeader(entry.getKey(), entry.getValue().get(0));
				}
			}
			InputStream inputStream = httpUrlConnection.getInputStream();
			message.reset(null, inputStream);
			if (message.prepareContent()) {
				inputStream.close();
				inputStream = message.getBody();
			}
			return new ExecutionContext(inputStream, httpUrlConnection);
		} catch (Exception e) {
			httpUrlConnection.close();
			throw e;
		}
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		if (nextActionIsPipelineStop) {
			InputStream inputStream = execContext.getResource();
			if (inputStream != null) {
				if (message.isSink()) {
					message.copyFrom(inputStream);
				} else {
					ByteArrayOutputStream bos = new ByteArrayOutputStream();
					message.reset(BodyType.OUTPUT_STREAM, bos);
					message.copyFrom(inputStream);
					message.reset(BodyType.INPUT_STREAM, bos.getByteArrayInputStream());
				}
			}
		}
	}

	@Override
	protected void close(Context context, ExecutionContext execContext, ESBMessage message, boolean exception) throws Exception {
		try (InputStream inputStream = execContext.getResource()) {
			// if inputStream was not consumed materialize it to avoid stream is closed exception
			if (inputStream != null && message.getBody() == inputStream) {
				message.getBodyAsByteArray(context);
			}
		} finally {
			execContext.<HttpUrlConnection> getResource2().close();
		}
	}

}
