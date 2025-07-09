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
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.StringTokenizer;
import java.util.regex.Pattern;

import com.artofarc.util.DataStructures;
import com.artofarc.util.WeakCache;

public class HttpConstants {

	public static final String HTTP_HEADER_ACCEPT = "Accept";
	public static final String HTTP_HEADER_ACCEPT_CHARSET = "Accept-Charset";
	public static final String HTTP_HEADER_ACCEPT_ENCODING = "Accept-Encoding";
	public static final String HTTP_HEADER_VARY = "Vary";
	public static final String HTTP_HEADER_TRANSFER_ENCODING = "Transfer-Encoding";
	public static final String HTTP_HEADER_CONTENT_LENGTH = "Content-Length";
	public static final String HTTP_HEADER_CONTENT_ENCODING = "Content-Encoding";
	public static final String HTTP_HEADER_CONTENT_TYPE = "Content-Type";
	public static final String HTTP_HEADER_CONTENT_TRANSFER_ENCODING = "Content-Transfer-Encoding";
	public static final String HTTP_HEADER_CONTENT_DISPOSITION = "Content-Disposition";
	public static final String HTTP_HEADER_SOAP_ACTION = "SOAPAction";
	public static final String HTTP_HEADER_RETRY_AFTER = "Retry-After";
	public static final String HTTP_HEADER_AUTHORIZATION = "Authorization";
	public static final String HTTP_HEADER_X_FORWARDED_FOR = "X-Forwarded-For";
	public static final String HTTP_HEADER_X_METHOD_OVERRIDE = "X-HTTP-Method-Override";

	public static final String HTTP_HEADER_CONTENT_TYPE_TEXT = "text/plain";
	public static final String HTTP_HEADER_CONTENT_TYPE_OCTET_STREAM = "application/octet-stream";
	public static final String HTTP_HEADER_CONTENT_TYPE_FORM_URLENCODED = "application/x-www-form-urlencoded";
	public static final String HTTP_HEADER_CONTENT_TYPE_XML = "application/xml";
	public static final String HTTP_HEADER_CONTENT_TYPE_JSON = "application/json";
	public static final String HTTP_HEADER_CONTENT_TYPE_SOAP11 = "text/xml";
	public static final String HTTP_HEADER_CONTENT_TYPE_SOAP12 = "application/soap+xml";
	public static final String HTTP_HEADER_CONTENT_TYPE_FI_SOAP11 = "application/fastinfoset";
	public static final String HTTP_HEADER_CONTENT_TYPE_FI_SOAP12 = "application/soap+fastinfoset";

	public static final String HTTP_HEADER_CONTENT_TYPE_PARAMETER_CHARSET = "charset=";
	public static final String HTTP_HEADER_CONTENT_TYPE_PARAMETER_ACTION = "action=";
	public static final String HTTP_HEADER_CONTENT_TYPE_PARAMETER_START = "start=";
	public static final String HTTP_HEADER_CONTENT_TYPE_PARAMETER_START_INFO = "start-info=";
	public static final String HTTP_HEADER_CONTENT_TYPE_PARAMETER_TYPE = "type=";

	public static final String HTTP_HEADER_CONTENT_DISPOSITION_PARAMETER_NAME = "name=";
	public static final String HTTP_HEADER_CONTENT_DISPOSITION_PARAMETER_FILENAME = "filename=";

	public static final String MEDIATYPE_APPLICATION = "application/";
	public static final String MEDIATYPE_TEXT = "text/";
	public static final String MEDIATYPE_MULTIPART = "multipart/";

	public static final int SC_OK = 200;
	public static final int SC_CREATED = 201;
	public static final int SC_ACCEPTED = 202;
	public static final int SC_NO_CONTENT = 204;
	public static final int SC_BAD_REQUEST = 400;
	public static final int SC_NOT_FOUND = 404;
	public static final int SC_METHOD_NOT_ALLOWED = 405;
	public static final int SC_NOT_ACCEPTABLE = 406;
	public static final int SC_UNSUPPORTED_MEDIA_TYPE = 415;
	public static final int SC_INTERNAL_SERVER_ERROR = 500;
	public static final int SC_GATEWAY_TIMEOUT = 504;

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
			if (j < 0) {
				return null;
			}
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

	private static final Pattern CRLFTAB = Pattern.compile("\r\n\t", Pattern.LITERAL);

	public static String unfoldHttpHeader(String httpHeader) {
		// https://datatracker.ietf.org/doc/html/rfc2616
		return CRLFTAB.matcher(httpHeader).replaceAll(" ");
	}

	public static String parseContentType(String contentType) {
		if (contentType != null) {
			final String type = getValueFromHttpHeader(contentType, HTTP_HEADER_CONTENT_TYPE_PARAMETER_TYPE);
			return (type != null ? type : getValueFromHttpHeader(contentType)).toLowerCase(Locale.ROOT);
		}
		return null;
	}

	public static long parseHttpDate(String date) {
		return DateTimeFormatter.RFC_1123_DATE_TIME.parse(date).getLong(ChronoField.INSTANT_SECONDS);
	}

	public static String toHttpDate(long epochSecond) {
		return DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC));
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
			return getValueFromHttpHeader(contentDisposition, HTTP_HEADER_CONTENT_DISPOSITION_PARAMETER_FILENAME);
		}
	}

	public static boolean isFastInfoset(String contentType) {
		return contentType != null && (contentType.startsWith(HTTP_HEADER_CONTENT_TYPE_FI_SOAP11) || contentType.startsWith(HTTP_HEADER_CONTENT_TYPE_FI_SOAP12));
	}

	public static boolean isNotSOAP11(String contentType) {
		if (contentType != null) {
			final String type = getValueFromHttpHeader(contentType).toLowerCase(Locale.ROOT);
			return !(type.equals(HTTP_HEADER_CONTENT_TYPE_SOAP11) || type.equals(HTTP_HEADER_CONTENT_TYPE_FI_SOAP11));
		}
		return false;
	}

	public static boolean isNotSOAP12(String contentType) {
		if (contentType != null) {
			final String type = getValueFromHttpHeader(contentType).toLowerCase(Locale.ROOT);
			return !(type.equals(HTTP_HEADER_CONTENT_TYPE_SOAP12) || type.equals(HTTP_HEADER_CONTENT_TYPE_FI_SOAP12));
		}
		return false;
	}

	public static boolean isNotXML(String contentType) {
		if (contentType != null) {
			final String type = getValueFromHttpHeader(contentType).toLowerCase(Locale.ROOT);
			return !(type.endsWith("/xml") || type.endsWith("+xml") || type.equals(HTTP_HEADER_CONTENT_TYPE_FI_SOAP11) || type.equals(HTTP_HEADER_CONTENT_TYPE_FI_SOAP12));
		}
		return false;
	}

	public static boolean isNotJSON(String contentType) {
		if (contentType != null) {
			final String type = getValueFromHttpHeader(contentType).toLowerCase(Locale.ROOT);
			return !(type.startsWith(MEDIATYPE_APPLICATION) && (type.endsWith("/json") || type.endsWith("+json")));
		}
		return false;
	}

	public static boolean isBinary(String contentType) {
		if (contentType != null) {
			final String type = getValueFromHttpHeader(contentType).toLowerCase(Locale.ROOT);
			return !(type.startsWith(MEDIATYPE_TEXT) || type.startsWith(MEDIATYPE_APPLICATION) && (type.endsWith("/xml") || type.endsWith("+xml") || type.endsWith("/json") || type.endsWith("+json") || type.equals(HTTP_HEADER_CONTENT_TYPE_FORM_URLENCODED)));
		}
		return false;
	}

	public static String determineCharset(String contentType) {
		if (contentType != null) {
			contentType = contentType.toLowerCase(Locale.ROOT);
			final String charset = getValueFromHttpHeader(contentType, HTTP_HEADER_CONTENT_TYPE_PARAMETER_CHARSET);
			if (charset == null) {
				final String type = getValueFromHttpHeader(contentType);
				if (type.startsWith(MEDIATYPE_APPLICATION)) {
					if (type.endsWith("/json") || type.endsWith("+json")) {
						return "UTF-8";
					}
				} else if (type.startsWith(MEDIATYPE_TEXT)) {
					// for xml we let the XML parser decide the encoding
					if (!(type.endsWith("/xml") || type.endsWith("+xml"))) {
						// https://www.ietf.org/rfc/rfc2068.txt (3.7.1)
						return "ISO-8859-1";
					}
				}
			}
			return charset;
		}
		return null;
	}

	public static boolean needsCharset(String contentType) {
		if (contentType != null && getValueFromHttpHeader(contentType, HTTP_HEADER_CONTENT_TYPE_PARAMETER_CHARSET) == null) {
			// https://www.iana.org/assignments/media-types/media-types.xhtml
			if (contentType.startsWith(MEDIATYPE_TEXT)) {
				return true;
			}
			if (contentType.startsWith(MEDIATYPE_APPLICATION)) {
				final String type = getValueFromHttpHeader(contentType);
				return type.endsWith("/xml") || type.endsWith("+xml");
			}
		}
		return false;
	}

	private static final WeakCache<String, ArrayList<Entry<String, BigDecimal>>> ACCEPT_CACHE = new WeakCache<String, ArrayList<Entry<String, BigDecimal>>>() {

		@Override
		public ArrayList<Entry<String, BigDecimal>> create(String accept) {
			ArrayList<Entry<String, BigDecimal>> result = new ArrayList<>();
			StringTokenizer tokenizer = new StringTokenizer(accept, ",");
			while (tokenizer.hasMoreTokens()) {
				String mediaRange = tokenizer.nextToken().trim();
				int i = mediaRange.lastIndexOf("q=");
				if (i < 0) {
					result.add(DataStructures.createEntry(mediaRange, BigDecimal.ONE));
				} else {
					try {
						// https://www.rfc-editor.org/rfc/rfc9110.html#name-accept
						BigDecimal quality = new BigDecimal(mediaRange.substring(i + 2));
						if (quality.scale() < 4 && quality.signum() >= 0 && BigDecimal.ONE.compareTo(quality) >= 0) {
							result.add(DataStructures.createEntry(mediaRange.substring(0, mediaRange.lastIndexOf(';', i)), quality.signum() > 0 ? quality : null));
						}
					} catch (NumberFormatException e) {
						// ignore
					}
				}
			}
			return result;
		}
	};

	private static ArrayList<Entry<String, BigDecimal>> parseAccept(String accept) {
		if (accept.contains("q=")) {
			return ACCEPT_CACHE.get(accept);
		} else {
			return ACCEPT_CACHE.create(accept);
		}
	}

	public static boolean isAcceptable(String accept, String value) {
		return getQuality(accept, value) != null;
	}

	public static BigDecimal getQuality(String accept, String value) {
		String bestMatch = null;
		BigDecimal bestQuality = null;
		int k = value.indexOf('/');
		if (k < 0 ) {
			for (Entry<String, BigDecimal> entry : parseAccept(accept)) {
				String mediaRange = entry.getKey();
				if (mediaRange.equals(value)) {
					return entry.getValue();
				} else if (mediaRange.equals("*")) {
					if (bestMatch == null || bestMatch.length() < mediaRange.length()) {
						bestMatch = mediaRange;
						bestQuality = entry.getValue();
					}
				}
			}
		} else {
			String valueType = value.substring(0, k);
			String valueSubType = value.substring(k + 1);
			for (Entry<String, BigDecimal> entry : parseAccept(accept)) {
				String mediaRange = entry.getKey();
				int j = mediaRange.indexOf('/');
				if (j >= 0) {
					String type = mediaRange.substring(0, j);
					if (type.equals("*") || type.equals(valueType)) {
						String subType = mediaRange.substring(j + 1);
						if (subType.equals(valueSubType)) {
							return entry.getValue();
						} else if (subType.equals("*")) {
							if (bestMatch == null || bestMatch.length() < mediaRange.length()) {
								bestMatch = mediaRange;
								bestQuality = entry.getValue();
							}
						}
					}
				}
			}
		}
		return bestQuality;
	}

	public static String getBestQualityValue(String accept) {
		String bestMatch = null;
		BigDecimal bestQuality = BigDecimal.ZERO;
		for (Entry<String, BigDecimal> entry : parseAccept(accept)) {
			BigDecimal quality = entry.getValue();
			if (quality != null) {
				String mediaRange = entry.getKey();
				int j = mediaRange.indexOf('/');
				if (j >= 0) {
					String type = mediaRange.substring(0, j);
					if (!type.equals("*")) {
						String subType = mediaRange.substring(j + 1);
						if (!subType.equals("*")) {
							if (quality.compareTo(bestQuality) > 0) {
								bestMatch = mediaRange;
								bestQuality = quality;
								if (bestQuality.compareTo(BigDecimal.ONE) == 0) {
									break;
								}
							}
						}
					}
				} else if (!mediaRange.equals("*")) {
					if (quality.compareTo(bestQuality) > 0) {
						bestMatch = mediaRange;
						bestQuality = quality;
						if (bestQuality.compareTo(BigDecimal.ONE) == 0) {
							break;
						}
					}
				}
			}
		}
		return bestMatch;
	}

}
