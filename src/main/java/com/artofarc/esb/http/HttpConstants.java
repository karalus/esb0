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
package com.artofarc.esb.http;

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

	public static final String HTTP_HEADER_X_FORWARDED_FOR = "X-Forwarded-For";

	public static final String HTTP_HEADER_X_METHOD_OVERRIDE = "X-HTTP-Method-Override";

	public static String getValueFromHttpHeader(String httpHeader, String key) {
		if (httpHeader != null) {
			int i = httpHeader.indexOf(key);
			if (i >= 0) {
				i += key.length();
				int j = httpHeader.indexOf(';', i);
				return j < 0 ? httpHeader.substring(i) : httpHeader.substring(i, j);
			}
		}
		return null;
	}

	public static String removeQuotes(String s) {
		return s != null && s.charAt(0) == '"' ? s.substring(1, s.length() - 1) : s;
	}

	public static String parseContentType(String contentType) {
		String type = getValueFromHttpHeader(contentType, HTTP_HEADER_CONTENT_TYPE_PARAMETER_TYPE);
		if (type != null) {
			contentType = removeQuotes(type);
		}
		return contentType;
	}

	public static boolean isFastInfoset(String contentType) {
		return contentType != null && (contentType.startsWith(HTTP_HEADER_CONTENT_TYPE_FI_SOAP11) || contentType.startsWith(HTTP_HEADER_CONTENT_TYPE_FI_SOAP12));
	}

	public static boolean isSOAP11(String contentType) {
		return contentType != null && (contentType.startsWith(SOAP_1_1_CONTENT_TYPE) || contentType.startsWith(HTTP_HEADER_CONTENT_TYPE_FI_SOAP11));
	}

	public static boolean isSOAP12(String contentType) {
		return contentType != null && (contentType.startsWith(SOAP_1_2_CONTENT_TYPE) || contentType.startsWith(HTTP_HEADER_CONTENT_TYPE_FI_SOAP12));
	}

	public static boolean isNotSOAP(String contentType) {
		return contentType != null && !(isSOAP11(contentType) || isSOAP12(contentType));
	}

}
