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

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import javax.json.*;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.BodyType;
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
		super.execute(context, execContext, message, nextActionIsPipelineStop);
		String contentType = message.getHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE);
		if (contentType != null && !contentType.startsWith(HttpConstants.HTTP_HEADER_CONTENT_TYPE_JSON)) {
			throw new ExecutionException(this, "Unexpected Content-Type: " + contentType);
		}
		JsonStructure json = Json.createReader(message.getBodyAsReader(context)).read();
		
		for (Assignment variable : _variables) {
			Object value = variable.getValueAsObject(json);
			if (value != null) {
				message.getVariables().put(variable._name, value);
			}
		}
		for (Assignment header : _headers) {
			Object value = header.getValueAsObject(json);
			if (value != null) {
				message.putHeader(header._name, value);
			}
		}
		if (_bodyExpr != null) {
			message.reset(BodyType.STRING, bindVariable(_bodyExpr, context, message));
			message.removeHeader(HttpConstants.HTTP_HEADER_CONTENT_LENGTH);
		}
	}

	protected final static class Assignment {
		private final String _name;
		private final ArrayList<String> _pointer = new ArrayList<>();
		//private final javax.json.JsonPointer _jsonPointer;
		
		public Assignment(String name, String jsonPointer) {
			_name = name;
			StringTokenizer st = new StringTokenizer(jsonPointer, "/");
			while (st.hasMoreTokens()) {
				try {
					_pointer.add(java.net.URLDecoder.decode(st.nextToken().replace("~1","/").replace("~0", "~"), "UTF-8"));
				} catch (UnsupportedEncodingException e) {
					//
				}
			}
			// needs javax.json v1.1 and Java 8
			//_jsonPointer = Json.createPointer(jsonPointer);
		}
		
//		private JsonValue getValue(JsonStructure json) {
//			return _jsonPointer.getValue(json);
//		}
			
		private JsonValue getValue(JsonStructure json) {
			JsonValue result = json;
			for (int i = 0; result != null && i < _pointer.size(); ++i) {
				String fragment = _pointer.get(i);
				switch (result.getValueType()) {
				case OBJECT:
					JsonObject jsonObject = (JsonObject) result;
					result = jsonObject.get(fragment);
					break;
				case ARRAY:
					JsonArray jsonArray = (JsonArray) result;
					try {
						int index = Integer.parseInt(fragment);
						result = jsonArray.size() > index ? jsonArray.get(index) : null;
					} catch (NumberFormatException e) {
						result = null;
					}
					break;
				default:
					result = null;
					break;
				}
			}
			if (result == null) {
				throw new JsonException("result is null");
			}
			return result;
		}
			
		public Object getValueAsObject(JsonStructure json) {
			try {
				JsonValue value = getValue(json);
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
					return jsonNumber.isIntegral() ? jsonNumber.longValueExact() : jsonNumber.bigDecimalValue();
				default:
					return value.toString();
				}
			} catch (JsonException e) {
				return null;
			}
		}

	}
	
}
