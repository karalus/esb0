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
package com.artofarc.esb.action;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import static com.artofarc.esb.http.HttpConstants.*;
import com.artofarc.esb.message.ESBMessage;
import static com.artofarc.util.W3CConstants.*;

public class WrapSOAPAction extends TransformAction {

	protected final boolean _soap12;

	public WrapSOAPAction(boolean soap12, boolean header, boolean singlePart) {
		this(soap12, header, singlePart, null);
	}

	protected WrapSOAPAction(boolean soap12, boolean header, boolean singlePart, String ns4rpc) {
		super(ns4rpc != null ? createXQueryRPCStyle(soap12, header, ns4rpc) : createXQueryDocumentStyle(soap12, header, singlePart));
		_soap12 = soap12;
	}

	private static String createXQueryDocumentStyle(boolean soap12, boolean header, boolean singlePart) {
		return (header ? "declare variable $header as element() external; " : "") + "<soapenv:Envelope xmlns:soapenv=\""
				+ (soap12 ? URI_NS_SOAP_1_2_ENVELOPE : URI_NS_SOAP_1_1_ENVELOPE) + "\">" + (header ? "{$header}" : "") + (singlePart ? "<soapenv:Body>" : "")
				+ "{.}" + (singlePart ? "</soapenv:Body>" : "") + "</soapenv:Envelope>";
	}

	private static String createXQueryRPCStyle(boolean soap12, boolean header, String ns) {
		return (header ? "declare variable $header as element() external; " : "")
				+ "declare variable $operation as xs:string external; <soapenv:Envelope xmlns:soapenv=\""
				+ (soap12 ? URI_NS_SOAP_1_2_ENVELOPE : URI_NS_SOAP_1_1_ENVELOPE) + "\">" + (header ? "{$header}" : "") + "<soapenv:Body>{element{QName('" + ns
				+ "', $operation)} {.} }</soapenv:Body></soapenv:Envelope>";
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		String contentType = parseContentType(message.getHeader(HTTP_HEADER_CONTENT_TYPE));
		message.clearHeaders();
		if (_soap12) {
			message.putHeader(HTTP_HEADER_CONTENT_TYPE, contentType == null || isNotSOAP12(contentType) ? HTTP_HEADER_CONTENT_TYPE_SOAP12 : contentType);
		} else {
			message.putHeader(HTTP_HEADER_CONTENT_TYPE, contentType == null || isNotSOAP11(contentType) ? HTTP_HEADER_CONTENT_TYPE_SOAP11 : contentType);
		}
		return super.prepare(context, message, inPipeline);
	}

}
