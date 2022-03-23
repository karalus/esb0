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

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;

/**
 * @see https://tools.ietf.org/html/rfc3986
 */
public final class URLUtils {

	private static final String UTF_8 = "UTF-8";
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

	// JDK11 allows for improvement
	public static String encode(String s) {
		try {
			return URLEncoder.encode(s, UTF_8);
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
	}

	public static String decode(String s) {
		try {
			return URLDecoder.decode(s, UTF_8);
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
	}

}
