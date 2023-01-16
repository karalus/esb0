/*
 * Copyright 2021 Andre Karalus
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

import java.util.ArrayList;
import java.util.List;

import javax.json.*;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import static com.artofarc.esb.http.HttpConstants.*;
import com.artofarc.esb.message.ESBMessage;

/**
 * Extract data from message using JSON Pointer.
 * 
 * @see <a href="https://tools.ietf.org/html/rfc6901">JavaScript Object Notation (JSON) Pointer</a>
 */
public class ProcessJsonAction extends Action {

	private final List<Assignment> _headers = new ArrayList<>();
	private final List<Assignment> _variables = new ArrayList<>();
	private final String _bodyExpr; 

	public ProcessJsonAction(String bodyExpr) {
		_pipelineStop = true;
		_bodyExpr = bodyExpr;
	}

	public void addHeader(String name, String jsonPointer)  {
		_headers.add(new Assignment(name, jsonPointer));
	}

	public void addVariable(String name, String jsonPointer)  {
		_variables.add(new Assignment(name, jsonPointer));
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		String contentType = message.getContentType();
		if (isNotJSON(contentType)) {
			throw new ExecutionException(this, "Unexpected Content-Type: " + contentType);
		}
		JsonValue json = message.getBodyAsJsonValue(context);
		for (Assignment variable : _variables) {
			Object value = variable.getValueAsObject((JsonStructure) json);
			if (value != null) {
				message.getVariables().put(variable._name, value);
			}
		}
		for (Assignment header : _headers) {
			Object value = header.getValueAsObject((JsonStructure) json);
			if (value != null) {
				message.putHeader(header._name, value);
			}
		}
		if (_bodyExpr != null) {
			Object body = eval(_bodyExpr, context, message);
			message.reset(null, body != null ? body.toString() : null);
			message.removeHeader(HTTP_HEADER_CONTENT_LENGTH);
		}
	}

	protected final static class Assignment {
		private final String _name;
		private final JsonPointer _jsonPointer;

		public Assignment(String name, String jsonPointer) {
			_name = name;
			_jsonPointer = Json.createPointer(jsonPointer);
		}

		public Object getValueAsObject(JsonStructure json) {
			try {
				return toObject(_jsonPointer.getValue(json));
			} catch (JsonException e) {
				return null;
			}
		}
	}

	public static Object toObject(JsonValue value) {
		switch (value.getValueType()) {
		case NULL:
			return null;
		case TRUE:
			return Boolean.TRUE;
		case FALSE:
			return Boolean.FALSE;
		case STRING:
			return ((JsonString) value).getString();
		case NUMBER:
			JsonNumber jsonNumber = (JsonNumber) value;
			// ArithmeticException if scale is 0 but number does not fit into long!
			return jsonNumber.isIntegral() ? jsonNumber.longValueExact() : jsonNumber.bigDecimalValue();
		default:
			return value;
		}
	}

}
