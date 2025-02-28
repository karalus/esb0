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

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.function.Function;

import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.XMLGregorianCalendar;

/**
 * @see https://tools.ietf.org/html/rfc3986
 */
public final class URLUtils {

	private static final BitSet PATH_DONT_NEED_ENCODING = new BitSet(128), QUERY_VALUE_DONT_NEED_ENCODING;

	static {
		for (int i = 'a'; i <= 'z'; ++i) {
			PATH_DONT_NEED_ENCODING.set(i);
		}
		for (int i = 'A'; i <= 'Z'; ++i) {
			PATH_DONT_NEED_ENCODING.set(i);
		}
		for (int i = '0'; i <= '9'; ++i) {
			PATH_DONT_NEED_ENCODING.set(i);
		}
		PATH_DONT_NEED_ENCODING.set('!');
		PATH_DONT_NEED_ENCODING.set('$');
		PATH_DONT_NEED_ENCODING.set('\'');
		PATH_DONT_NEED_ENCODING.set('(');
		PATH_DONT_NEED_ENCODING.set(')');
		PATH_DONT_NEED_ENCODING.set('*');
		PATH_DONT_NEED_ENCODING.set(',');
		PATH_DONT_NEED_ENCODING.set(';');
		PATH_DONT_NEED_ENCODING.set('=');
		PATH_DONT_NEED_ENCODING.set('-');
		PATH_DONT_NEED_ENCODING.set('.');
		PATH_DONT_NEED_ENCODING.set('_');
		PATH_DONT_NEED_ENCODING.set('~');
		PATH_DONT_NEED_ENCODING.set(':');
		PATH_DONT_NEED_ENCODING.set('@');
		// Common base
		QUERY_VALUE_DONT_NEED_ENCODING = (BitSet) PATH_DONT_NEED_ENCODING.clone();
		// Allowed within path
		PATH_DONT_NEED_ENCODING.set('&');
		PATH_DONT_NEED_ENCODING.set('+');
		// Buggy Servers might struggle but it's valid!
		QUERY_VALUE_DONT_NEED_ENCODING.set('/');
		QUERY_VALUE_DONT_NEED_ENCODING.set('?');
	}

	private URLUtils() {
	}

	public static String normalizePathSegment(String s) throws URISyntaxException {
		// http://en.wikipedia.org/wiki/URL_normalization#Normalizations_that_Preserve_Semantics
		URI uri = new URI(s), normalizedUri = uri.normalize();
		return uri != normalizedUri ? normalizedUri.toString() : s;
	}

	public static String encodePathSegment(String s) {
		return encode(s, PATH_DONT_NEED_ENCODING);
	}

	public static String encodeQueryValue(String s) {
		return encode(s, QUERY_VALUE_DONT_NEED_ENCODING);
	}

	private static String encode(String s, BitSet dontNeedEnconding) {
		boolean modified = false;
		StringBuilder result = new StringBuilder();
		for (int i = 0; i < s.length(); ++i) {
			char c = s.charAt(i);
			if (dontNeedEnconding.get(c)) {
				result.append(c);
			} else {
				if (c < 128) {
					result.append('%').append(convertNibble2Char(c >> 4)).append(convertNibble2Char(c));
				} else {
					CharBuffer charBuffer = CharBuffer.wrap(s, i, (Character.isHighSurrogate(c) ? ++i : i) + 1);
					ByteBuffer byteBuffer = StandardCharsets.UTF_8.encode(charBuffer);
					do {
						byte b = byteBuffer.get();
						result.append('%').append(convertNibble2Char(b >> 4)).append(convertNibble2Char(b));
					} while (byteBuffer.hasRemaining());
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
		URLEncodedStringBuilder builder = new URLEncodedStringBuilder(URLUtils::encode);
		StringTokenizer st = new StringTokenizer(parameters, ",");
		while (st.hasMoreTokens()) {
			String varName = st.nextToken();
			builder.add(varName, variables.get(varName));
		}
		return builder.toString();
	}

	public final static class URLEncodedStringBuilder {
		private final StringBuilder sb = new StringBuilder();
		private final Function<String, String> _encoder;

		public URLEncodedStringBuilder(Function<String, String> encoder) {
			_encoder = encoder;
		}

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
			sb.append(key).append('=').append(_encoder.apply(value.toString()));
		}

		@Override
		public String toString() {
			return sb.length() > 0 ? sb.toString() : null;
		}
	}

}
