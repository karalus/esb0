/*
 * Copyright 2023 Andre Karalus
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

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.ByteArrayOutputStream;

public abstract class TerminalAction extends Action {

	protected TerminalAction() {
		_pipelineStop = true;
		_offeringSink = true;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) {
		if (inPipeline) {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			message.reset(BodyType.OUTPUT_STREAM, bos);
			return new ExecutionContext(bos);
		}
		return null;
	}

	@Override
	protected final void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		if (execContext != null && execContext.getResource() instanceof ByteArrayOutputStream) {
			ByteArrayOutputStream bos = execContext.getResource();
			message.reset(BodyType.INPUT_STREAM, bos.getByteArrayInputStream());
			message.evaluateContentType();
		}
		execute(context, message);
	}

	protected void execute(Context context, ESBMessage message) throws Exception {
	}

}
