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

public class ExecutionException extends Exception {

	private static final long serialVersionUID = 1L;

	private final Action _action;

	public ExecutionException(Action action, String message, Throwable cause) {
		super(message, cause);
		_action = action;
	}

	public ExecutionException(Action action, String message) {
		super(message);
		_action = action;
	}

	public final Action getAction() {
		return _action;
	}

	@Override
	public String getMessage() {
		return _action + ": " + super.getMessage();
	}

}
