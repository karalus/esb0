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
package com.artofarc.esb.http;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Locale;

public class HttpConstants {

	/**
	 * The namespace identifier for the SOAP 1.1 envelope.
	 */
	public static final String URI_NS_SOAP_1_1_ENVELOPE = "http://schemas.xmlsoap.org/soap/envelope/";

	/**
	 * The namespace identifier for the SOAP 1.2 envelope.
	 */
	public static final String URI_NS_SOAP_1_2_ENVELOPE = "http://www.w3.org/2003/05/soap-envelope";

	/**
	 * The media type of the <code>Content-Type</code> MIME header in SOAP 1.1.
	 */
	public static final String SOAP_1_1_CONTENT_TYPE = "text/xml";

	/**
	 * The media type of the <code>Content-Type</code> MIME header in SOAP 1.2.
	 */
	public static final String SOAP_1_2_CONTENT_TYPE = "application/soap+xml";


	public static final String HTTP_HEADER_ACCEPT_CHARSET = "Accept-Charset";

	public static final String HTTP_HEADER_ACCEPT_ENCODING = "Accept-Encoding";

	public static final String HTTP_HEADER_TRANSFER_ENCODING = "Transfer-Encoding";

	public static final String HTTP_HEADER_CONTENT_LENGTH = "Content-Length";

	public static final String HTTP_HEADER_CONTENT_ENCODING = "Content-Encoding";

	public static final String HTTP_HEADER_CONTENT_TYPE = "Content-Type";

	public static final String HTTP_HEADER_CONTENT_TRANSFER_ENCODING = "Content-Transfer-Encoding";

	public static final String HTTP_HEADER_CONTENT_DISPOSITION = "Content-Disposition";

	public static final String HTTP_HEADER_CONTENT_TYPE_TEXT = "text/plain";

	public static final String HTTP_HEADER_CONTENT_TYPE_JSON = "application/json";

	public static final String HTTP_HEADER_CONTENT_TYPE_FI_SOAP11 = "application/fastinfoset";

	public static final String HTTP_HEADER_CONTENT_TYPE_FI_SOAP12 = "application/soap+fastinfoset";

	public static final String HTTP_HEADER_CONTENT_TYPE_PARAMETER_CHARSET = "charset=";

	public static final String HTTP_HEADER_CONTENT_TYPE_PARAMETER_ACTION = "action=";

	public static final String HTTP_HEADER_CONTENT_TYPE_PARAMETER_START = "start=";

	public static final String HTTP_HEADER_CONTENT_TYPE_PARAMETER_START_INFO = "start-info=";

	public static final String HTTP_HEADER_CONTENT_TYPE_PARAMETER_TYPE = "type=";

	public static final String HTTP_HEADER_CONTENT_PARAMETER_NAME = "name=";

	public static final String HTTP_HEADER_ACCEPT = "Accept";

	public static final String HTTP_HEADER_SOAP_ACTION = "SOAPAction";

	public static final String HTTP_HEADER_RETRY_AFTER = "Retry-After";

	public static final String HTTP_HEADER_AUTHORIZATION = "Authorization";

	public static final String HTTP_HEADER_X_FORWARDED_FOR = "X-Forwarded-For";

	public static final String HTTP_HEADER_X_METHOD_OVERRIDE = "X-HTTP-Method-Override";

	public static final String MEDIATYPE_APPLICATION = "application/";

	public static final String MEDIATYPE_TEXT = "text/";

	public static final String MEDIATYPE_WILDCARD = "*/*";

	private static int findNextDelim(String s, int i) {
		for (; i < s.length(); ++i) {
			final char c = s.charAt(i);
			if (c == ';' || c == ',' || c == '=') {
				return i;
			}
		}
		return -1;
	}

	private static String parseValueFromHttpHeader(String s, String key, int len) {
		int i = findNextDelim(s, 0) + 1;
		if (i == 0) return null;
		StringBuilder value = new StringBuilder();
		while (i < s.length()) {
			while (Character.isWhitespace(s.charAt(i))) ++i;
			final int j = findNextDelim(s, i);
			int k = j + 1;
			boolean quoted = false, escaped = false;
			while (k < s.length()) {
				final char c = s.charAt(k++);
				if (c == '"' && !escaped) {
					quoted = !quoted;
					continue;
				}
				if (!quoted && !escaped && (c == ';' || c == ',')) {
					break;
				}
				if (!(escaped = quoted && !escaped && c == '\\')) {
					value.append(c);
				}
			}
			if (j - i == len && s.regionMatches(true, i, key, 0, len)) {
				return value.length() > 0 ? value.toString() : "";
			}
			i = k;
			value.setLength(0);
		}
		return null;
	}

	public static String getValueFromHttpHeader(String httpHeader) {
		final int i = findNextDelim(httpHeader, 0);
		return i < 0 ? httpHeader : httpHeader.substring(0, i);
	}

	public static String getValueFromHttpHeader(String httpHeader, String key) {
		return httpHeader != null ? parseValueFromHttpHeader(httpHeader, key, key.length() - 1) : null;
	}

	public static String parseContentType(String contentType) {
		if (contentType != null) {
			final String type = getValueFromHttpHeader(contentType, HTTP_HEADER_CONTENT_TYPE_PARAMETER_TYPE);
			return (type != null ? type : getValueFromHttpHeader(contentType)).toLowerCase(Locale.ROOT);
		}
		return null;
	}

	public static String getFilename(String contentDisposition) throws UnsupportedEncodingException {
		// https://www.rfc-editor.org/rfc/rfc5987
		String filename = getValueFromHttpHeader(contentDisposition, "filename*=");
		if (filename != null) {
			int i = filename.indexOf('\'');
			String enc = filename.substring(0, i);
			// skip locale
			i = filename.indexOf('\'', i + 1);
			return URLDecoder.decode(filename.substring(i + 1), enc);
		} else {
			return getValueFromHttpHeader(contentDisposition, "filename=");
		}
	}

	public static boolean isFastInfoset(String contentType) {
		return contentType != null && (contentType.startsWith(HTTP_HEADER_CONTENT_TYPE_FI_SOAP11) || contentType.startsWith(HTTP_HEADER_CONTENT_TYPE_FI_SOAP12));
	}

	public static boolean isSOAP11(String contentType) {
		if (contentType != null) {
			final String type = getValueFromHttpHeader(contentType);
			return type.equals(SOAP_1_1_CONTENT_TYPE) || type.equals(HTTP_HEADER_CONTENT_TYPE_FI_SOAP11);
		}
		return false;
	}

	public static boolean isSOAP12(String contentType) {
		if (contentType != null) {
			final String type = getValueFromHttpHeader(contentType);
			return type.equals(SOAP_1_2_CONTENT_TYPE) || type.equals(HTTP_HEADER_CONTENT_TYPE_FI_SOAP12);
		}
		return false;
	}

	public static boolean isNotXML(String contentType) {
		if (contentType != null) {
			final String type = getValueFromHttpHeader(contentType);
			return !(type.endsWith("/xml") || type.endsWith("+xml") || type.equals(HTTP_HEADER_CONTENT_TYPE_FI_SOAP11) || type.equals(HTTP_HEADER_CONTENT_TYPE_FI_SOAP12));
		}
		return false;
	}

	public static boolean isNotJSON(String contentType) {
		if (contentType != null) {
			final String type = getValueFromHttpHeader(contentType);
			return !(type.startsWith(MEDIATYPE_APPLICATION) && (type.endsWith("/json") || type.endsWith("+json")));
		}
		return false;
	}

}
