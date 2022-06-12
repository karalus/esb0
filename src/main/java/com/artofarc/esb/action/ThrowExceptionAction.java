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

import com.artofarc.esb.context.Context;
import com.artofarc.esb.message.ESBMessage;

public class ThrowExceptionAction extends TerminalAction {

	private final String _message;

	public ThrowExceptionAction(String message) {
		_message = message;
	}

	@Override
	protected void execute(Context context, ESBMessage message) throws Exception {
		throw new ExecutionException(this, (String) eval(_message, context, message));
	}

}
