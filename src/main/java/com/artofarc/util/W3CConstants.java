/*
 * Copyright 2023 Andre Karalus
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

public interface W3CConstants {

	/**
	 * The namespace identifier for the SOAP 1.1 envelope.
	 */
	String URI_NS_SOAP_1_1_ENVELOPE = "http://schemas.xmlsoap.org/soap/envelope/";

	/**
	 * The namespace identifier for the SOAP 1.2 envelope.
	 */
	String URI_NS_SOAP_1_2_ENVELOPE = "http://www.w3.org/2003/05/soap-envelope";

	String URI_NS_XOP = "http://www.w3.org/2004/08/xop/include";
	String NAME_INCLUDE = "Include";
	String NAME_HREF = "href";

	static boolean isXOPInclude(String uri, String localName) {
		return NAME_INCLUDE.equals(localName) && URI_NS_XOP.equals(uri);
	}

	String URI_NS_XMLMIME = "http://www.w3.org/2005/05/xmlmime";
	String NAME_CONTENT_TYPE = "contentType";

}
