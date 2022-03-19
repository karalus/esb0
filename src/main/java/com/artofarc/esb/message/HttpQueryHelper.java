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

import java.util.ArrayList;
import java.util.StringTokenizer;

import javax.xml.datatype.XMLGregorianCalendar;

import com.artofarc.util.URLUtils;

public class HttpQueryHelper {

	@SuppressWarnings("unchecked")
	public static void parseQueryString(ESBMessage message, boolean generic) {
		String queryString = message.getVariable(ESBConstants.QueryString);
		if (queryString != null) {
			int count = 0;
			StringTokenizer st = new StringTokenizer(queryString, "&");
			while (st.hasMoreTokens()) {
				String pair = st.nextToken();
				final int i = pair.indexOf("=");
				final String key, value;
				if (i > 0) {
					key = URLUtils.decode(pair.substring(0, i));
					value = URLUtils.decode(pair.substring(i + 1));
				} else {
					key = URLUtils.decode(pair);
					value = null;
				}
				if (generic) {
					message.getVariables().put(ESBConstants.QueryString + '#' + ++count, key);
					message.getVariables().put(ESBConstants.QueryString + '#' + ++count, value);
				} else {
					Object old = message.getVariables().get(key);
					if (old != null) {
						ArrayList<Object> list; 
						if (old instanceof ArrayList) {
							list = (ArrayList<Object>) old;
						} else {
							list = new ArrayList<>();
							list.add(old);
							message.getVariables().put(key, list);
						}
						list.add(value);
					} else {
						message.getVariables().put(key, value);
					}
				}
			}
			if (generic) {
				message.getVariables().put(ESBConstants.QueryString + '#', count);
			}
		}
	}

	public static String getQueryString(ESBMessage message) {
		String queryString = message.getVariable(ESBConstants.QueryString);
		if (queryString == null || queryString.isEmpty()) {
			queryString = null;
			String httpQueryParameter = message.getVariable(ESBConstants.HttpQueryParameter);
			if (httpQueryParameter != null) {
				StringBuilder qs = new StringBuilder();
				StringTokenizer st = new StringTokenizer(httpQueryParameter, ",");
				while (st.hasMoreTokens()) {
					String varName = st.nextToken();
					Object value = message.getVariables().get(varName);
					if (value != null) {
						varName = URLUtils.encode(varName);
						if (value instanceof Iterable) {
							for (Object object : (Iterable<?>) value) {
								append(qs, varName, object);
							}
						} else {
							append(qs, varName, value);
						}
					}
				}
				if (qs.length() > 0) {
					queryString = qs.toString();
				}
			}
		}
		return queryString;
	}

	private static void append(StringBuilder sb, String key, Object value) {
		if (value instanceof XMLGregorianCalendar) {
			com.artofarc.esb.json.Xml2JsonTransformer.omitTZfromDate((XMLGregorianCalendar) value);
		}
		if (sb.length() > 0) {
			sb.append('&');
		}
		sb.append(key).append('=').append(URLUtils.encode(value.toString()));
	}

}
