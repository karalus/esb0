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
package com.artofarc.esb.message;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.StringTokenizer;

import javax.xml.datatype.XMLGregorianCalendar;

import com.artofarc.esb.json.Xml2JsonTransformer;

public class HttpQueryHelper {

	private static final String UTF_8 = "UTF-8";

	public static void parseQueryString(ESBMessage message, boolean generic) throws UnsupportedEncodingException {
		String queryString = message.getVariable(ESBConstants.QueryString);
		if (queryString != null) {
			int count = 0;
			StringTokenizer st = new StringTokenizer(queryString, "&");
			while (st.hasMoreTokens()) {
				String pair = st.nextToken();
				final int i = pair.indexOf("=");
				final String key, value;
				if (i > 0) {
					key = URLDecoder.decode(pair.substring(0, i), UTF_8);
					value = URLDecoder.decode(pair.substring(i + 1), UTF_8);
				} else {
					key = URLDecoder.decode(pair, UTF_8);
					value = null;
				}
				if (generic) {
					message.getVariables().put(ESBConstants.QueryString + '#' + ++count, key);
					message.getVariables().put(ESBConstants.QueryString + '#' + ++count, value);
				} else {
					message.getVariables().put(key, value);
				}
			}
			if (generic) {
				message.getVariables().put(ESBConstants.QueryString + '#', count);
			}
		}
	}

	public static String getQueryString(ESBMessage message) throws UnsupportedEncodingException {
		String queryString = message.getVariable(ESBConstants.QueryString);
		if (queryString == null || queryString.isEmpty()) {
			queryString = null;
			String httpQueryParameter = message.getVariable(ESBConstants.HttpQueryParameter);
			if (httpQueryParameter != null) {
				StringTokenizer st = new StringTokenizer(httpQueryParameter, ",");
				while (st.hasMoreTokens()) {
					String varName = st.nextToken();
					Object value = message.getVariables().get(varName);
					if (value != null) {
						if (value instanceof XMLGregorianCalendar) {
							Xml2JsonTransformer.omitTZfromDate((XMLGregorianCalendar) value);
						}
						String valStr = URLEncoder.encode(varName, UTF_8) + "=" + URLEncoder.encode(value.toString(), UTF_8);
						if (queryString != null) {
							queryString += "&" + valStr;
						} else {
							queryString = valStr;
						}
					}
				}
			}
		}
		return queryString;
	}

}
