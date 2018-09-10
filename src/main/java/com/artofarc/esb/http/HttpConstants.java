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

	public static final String HTTP_HEADER_ACCEPT_ENCODING = "Accept-Encoding";

	public static final String HTTP_HEADER_TRANSFER_ENCODING = "Transfer-Encoding";

	public static final String HTTP_HEADER_CONTENT_LENGTH = "Content-Length";

	public static final String HTTP_HEADER_CONTENT_ENCODING = "Content-Encoding";

	public static final String HTTP_HEADER_CONTENT_TYPE = "Content-Type";
	
	public static final String HTTP_HEADER_CONTENT_TYPE_PARAMETER_CHARSET = "charset=";

	public static final String HTTP_HEADER_CONTENT_TYPE_PARAMETER_ACTION = "action=";

	public static final String HTTP_HEADER_CONTENT_TYPE_PARAMETER_START = "start=";

	public static final String HTTP_HEADER_ACCEPT = "Accept";

	public static final String HTTP_HEADER_SOAP_ACTION = "SOAPAction";
	
	public static final String HTTP_HEADER_X_FORWARDED_FOR = "X-Forwarded-For";

	public static final String getValueFromHttpHeader(String httpHeader, String key) {
		int i = httpHeader.indexOf(key);
		if (i >= 0) {
			i += key.length();
			int j = httpHeader.indexOf(';', i);
			return j < 0 ? httpHeader.substring(i) : httpHeader.substring(i, j);
		}
		return null;
	}

}
