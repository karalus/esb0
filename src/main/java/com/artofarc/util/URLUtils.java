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
package com.artofarc.util;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Map;
import java.util.StringTokenizer;

import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.XMLGregorianCalendar;

/**
 * @see https://tools.ietf.org/html/rfc3986
 */
public final class URLUtils {

	private static final BitSet DONT_NEED_ENCODING = new BitSet(128);

	static {
		for (int i = 'a'; i <= 'z'; ++i) {
			DONT_NEED_ENCODING.set(i);
		}
		for (int i = 'A'; i <= 'Z'; ++i) {
			DONT_NEED_ENCODING.set(i);
		}
		for (int i = '0'; i <= '9'; ++i) {
			DONT_NEED_ENCODING.set(i);
		}
		DONT_NEED_ENCODING.set('!');
		DONT_NEED_ENCODING.set('$');
		DONT_NEED_ENCODING.set('&');
		DONT_NEED_ENCODING.set('\'');
		DONT_NEED_ENCODING.set('(');
		DONT_NEED_ENCODING.set(')');
		DONT_NEED_ENCODING.set('*');
		DONT_NEED_ENCODING.set('+');
		DONT_NEED_ENCODING.set(',');
		DONT_NEED_ENCODING.set(';');
		DONT_NEED_ENCODING.set('=');
		DONT_NEED_ENCODING.set('-');
		DONT_NEED_ENCODING.set('.');
		DONT_NEED_ENCODING.set('_');
		DONT_NEED_ENCODING.set('~');
		DONT_NEED_ENCODING.set(':');
		DONT_NEED_ENCODING.set('@');
	}

	private URLUtils() {
	}

	public static String encodePathSegment(String s) {
		boolean modified = false;
		StringBuilder result = new StringBuilder();
		for (int i = 0; i < s.length(); ++i) {
			char c = s.charAt(i);
			if (DONT_NEED_ENCODING.get(c)) {
				result.append(c);
			} else {
				for (byte b : String.valueOf(c).getBytes(StandardCharsets.UTF_8)) {
					result.append('%').append(convertNibble2Char(b >> 4)).append(convertNibble2Char(b));
				}
				modified = true;
			}
		}
		return modified ? result.toString() : s;
	}

	static char convertNibble2Char(int n) {
		char c = Character.forDigit(n & 0xF, 16);
		return Character.isLowerCase((int) c) ? (char) (c - 32) : c;
	}

	public static String encode(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	public static String decode(String s) {
		return URLDecoder.decode(s, StandardCharsets.UTF_8);
	}

	@SuppressWarnings("unchecked")
	public static void parseURLEncodedString(String urlEncodedString, Map<String, Object> variables, String genericVariable) {
		int count = 0;
		StringTokenizer st = new StringTokenizer(urlEncodedString, "&");
		while (st.hasMoreTokens()) {
			final String pair = st.nextToken();
			final int i = pair.indexOf("=");
			final String key, value;
			if (i > 0) {
				key = decode(pair.substring(0, i));
				value = decode(pair.substring(i + 1));
			} else {
				key = decode(pair);
				value = null;
			}
			if (genericVariable != null) {
				variables.put(genericVariable + '#' + ++count, key);
				variables.put(genericVariable + '#' + ++count, value);
			} else {
				Object old = variables.get(key);
				if (old != null) {
					ArrayList<Object> list; 
					if (old instanceof ArrayList) {
						list = (ArrayList<Object>) old;
					} else {
						list = new ArrayList<>();
						list.add(old);
						variables.put(key, list);
					}
					list.add(value);
				} else {
					variables.put(key, value);
				}
			}
		}
		if (genericVariable != null) {
			variables.put(genericVariable + '#', count);
		}
	}

	public static String createURLEncodedString(Map<String, Object> variables, String parameters) {
		URLEncodedStringBuilder builder = new URLEncodedStringBuilder();
		StringTokenizer st = new StringTokenizer(parameters, ",");
		while (st.hasMoreTokens()) {
			String varName = st.nextToken();
			builder.add(varName, variables.get(varName));
		}
		return builder.toString();
	}

	public final static class URLEncodedStringBuilder {
		private final StringBuilder sb = new StringBuilder();

		public void add(String key, Object value) {
			if (value != null) {
				key = encode(key);
				if (value instanceof Iterable) {
					for (Object object : (Iterable<?>) value) {
						append(key, object);
					}
				} else {
					append(key, value);
				}
			} else {
				sb.append(key);
			}
		}

		private void append(String key, Object value) {
			if (value instanceof XMLGregorianCalendar) {
				// omit time zone from Date
				((XMLGregorianCalendar) value).setTimezone(DatatypeConstants.FIELD_UNDEFINED);
			}
			if (sb.length() > 0) {
				sb.append('&');
			}
			sb.append(key).append('=').append(encode(value.toString()));
		}

		@Override
		public String toString() {
			return sb.length() > 0 ? sb.toString() : null;
		}
	}

}
