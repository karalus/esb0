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

import java.util.Collection;
import java.util.Map.Entry;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;

public class SetMessageAction extends Action {

	private final Collection<Entry<String, String>> _headers;
	private final String _body; 

	public SetMessageAction(Collection<Entry<String, String>> headers, String body) {
		_pipelineStop = true;
		_headers = headers;
		_body = body;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		Collection<Entry<String,String>> _headers2 = _headers;
		for (Entry<String, String> header : _headers2) {
			message.putHeader(header.getKey(), bindVariable(header.getValue(), message));
		}
		ExecutionContext execContext = null;
		if (_body != null) {
			execContext = new ExecutionContext(bindVariable(_body, message));
			message.reset(BodyType.STRING, execContext.getResource());
		}
		return execContext;
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		if (execContext != null) {
			message.reset(BodyType.STRING, execContext.getResource());
		}
	}

}
