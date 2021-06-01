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

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;

public class PostSOAPHttpAction extends UnwrapSOAPAction {

	public PostSOAPHttpAction(boolean soap12, boolean singlePart) {
		super(soap12, singlePart);
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		Integer httpResponseCode = message.getVariable(ESBConstants.HttpResponseCode);
		switch (httpResponseCode) {
		case HttpURLConnection.HTTP_OK:
		case HttpURLConnection.HTTP_ACCEPTED:
		case HttpURLConnection.HTTP_INTERNAL_ERROR:
			return super.prepare(context, message, inPipeline);
		default:
			throw new ExecutionException(this, "HTTP Response Code not covered by SOAP protocol, was " + httpResponseCode);
		}
	}

	@Override
	protected String determineOperation(ESBMessage message) {
		return null;
	}

}
