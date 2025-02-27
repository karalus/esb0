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
package com.artofarc.esb.message;

import java.util.StringTokenizer;

import com.artofarc.esb.context.Context;
import com.artofarc.util.ReflectionUtils;
import com.artofarc.util.URLUtils;

public abstract class Evaluator<E extends Exception> {

	protected abstract E createException(String message);

	public final Object eval(String exp, Context context, ESBMessage message) throws Exception {
		StringBuilder builder = new StringBuilder();
		for (int pos = 0;;) {
			int i = exp.indexOf("${", pos);
			if (i < 0) {
				if (pos == 0) return exp;
				builder.append(exp.substring(pos));
				break;
			}
			if (i > pos) builder.append(exp.substring(pos, i));
			int j = exp.indexOf('}', i);
			if (j < 0) throw new IllegalArgumentException("Matching } is missing");
			String path = exp.substring(i + 2, j);
			int k = path.indexOf('.');
			String name = k < 0 ? path : path.substring(0, k);
			Object value;
			switch (name) {
			case "null":
				value = null;
				break;
			case "esbMessage":
				value = message;
				break;
			case "rawBody":
				value = k > 0 ? message.getBody() : message.cloneBody(context, false);
				break;
			case "body":
				value = message.getBodyAsString(context);
				break;
			case "attachments":
				value = message.getAttachments();
				break;
			default:
				value = resolve(message, name, true);
				if (value == null && (k >= 0 || name.indexOf('_') >= 0 || name.startsWith("java:"))) {
					// interpret dots as separators
					value = context.getGlobalContext().getProperty(path);
					k = -1;
				}
				break;
			}
			boolean standalone = ++j == exp.length() && pos == 0 && i == 0;
			if (value == null && !standalone) {
				throw createException("name could not be resolved: " + name);
			}
			if (k >= 0) {
				ReflectionUtils.ParamResolver<E> paramResolver = path.indexOf('(', k) < 0 ? null : new ReflectionUtils.ParamResolver<E>() {

					@Override
					public Object resolve(String param) throws E {
						char firstChar = param.charAt(0);
						if (firstChar == '\'') {
							return param.substring(1, param.length() - 1);
						} else if (Character.isDigit(firstChar) || firstChar == '-') {
							return Integer.valueOf(param);
						} else if ("null".equals(param)) {
							return null;
						}
						return Evaluator.this.resolve(message, param, true);
					}
				};
				value = ReflectionUtils.eval(value, path, k + 1, paramResolver);
			}
			if (standalone) {
				return value;
			}
			builder.append(value.toString());
			pos = j;
		}
		return builder.toString();
	}

	public final Object resolve(ESBMessage message, String name, boolean checkAmbiguity) throws E {
		Object value = message.getVariable(name);
		if (value == null) {
			switch (name) {
			case "contentType":
				value = message.getContentType();
				break;
			case "contentLength":
				value = message.getLength();
				break;
			case "rawBody":
				if (message.getBodyType() == BodyType.INPUT_STREAM) {
					try {
						value = message.getUncompressedInputStream(message.getBody());
					} catch (java.io.IOException e) {
						throw createException("InputStream not valid: " + e.getMessage());
					}
				} else {
					value = message.getBody();
				}
				message.reset(BodyType.INVALID, null);
				break;
			default:
				value = message.getHeader(name);
				break;
			}
		} else if (checkAmbiguity) {
			Object header = message.getHeader(name);
			if (header != null && !value.equals(header)) {
				throw createException("name could not unambiguously be resolved: " + name);
			}
		}
		return value;
	}

	public final String createURLEncodedString(Context context, ESBMessage message, String parameters) throws Exception {
		URLUtils.URLEncodedStringBuilder builder = new URLUtils.URLEncodedStringBuilder(URLUtils::encodeQueryValue);
		StringTokenizer st = new StringTokenizer(parameters, ",");
		while (st.hasMoreTokens()) {
			String varName = st.nextToken();
			Object value;
			int i = varName.indexOf('=');
			if (i > 0) {
				value = eval(varName.substring(i + 1), context, message);
				varName = varName.substring(0, i);
			} else {
				value = message.getVariable(varName);
			}
			builder.add(varName, value);
		}
		return builder.toString();
	}

}
