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

import java.io.ByteArrayOutputStream;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;

public abstract class TerminalAction extends Action {

	protected TerminalAction() {
		_pipelineStop = true;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		if (inPipeline) {
			ByteArrayOutputStream bos = new ByteArrayOutputStream(ESBMessage.MTU);
			message.reset(BodyType.OUTPUT_STREAM, bos);
			return new ExecutionContext(bos);
		} else {
			return null;
		}
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, final ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		if (execContext != null) {
			ByteArrayOutputStream bos = execContext.getResource();
			message.reset(BodyType.BYTES, bos.toByteArray());
			message.setCharset(message.getSinkEncoding());
		}
	}

}
