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
package com.artofarc.esb.action;

import java.net.HttpURLConnection;
import java.util.Map;

import javax.xml.soap.SOAPConstants;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.ESBVariableConstants;
import com.artofarc.util.DOMUtil;

public class PostSOAP11HttpAction extends UnwrapSOAP11Action {

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		Integer httpResponseCode = message.getVariable(ESBVariableConstants.HttpResponseCode);
		switch (httpResponseCode) {
		case HttpURLConnection.HTTP_OK:
		case HttpURLConnection.HTTP_ACCEPTED:
			return super.prepare(context, message, inPipeline);
		case HttpURLConnection.HTTP_INTERNAL_ERROR:
		case HttpURLConnection.HTTP_NOT_FOUND:
			// TODO!
			// setNextAction(getErrorHandler());
			// operation="SOAP-ENV:Fault"
			return super.prepare(context, message, inPipeline);
		default:
			throw new ExecutionException(this, "HTTP Response Code not covered by SOAP 1.1, was " + httpResponseCode);
		}
	}

	public void _execute(Context context, ESBMessage message) throws Exception {
		String contentType = (String) message.getHeaders().get(HttpConstants.HTTP_HEADER_CONTENT_TYPE);
		if (!contentType.startsWith(SOAPConstants.SOAP_1_1_CONTENT_TYPE)) {
			throw new ExecutionException(this, "Unexpected Content-Type: " + contentType);
		}
		Document document = message.getBodyAsDOM(context);
		Integer httpResponseCode = message.getVariable(ESBVariableConstants.HttpResponseCode);
		switch (httpResponseCode) {
		case HttpURLConnection.HTTP_OK:
		case HttpURLConnection.HTTP_ACCEPTED:
			Element body = DOMUtil.getLastChildElement(DOMUtil.getLastChildElement(document.removeChild(document.getDocumentElement())));
			document.appendChild(body);
			break;
		case HttpURLConnection.HTTP_INTERNAL_ERROR:
			ExecutionException exception = new ExecutionException(this, "SOAP Fault");
			Element fault = DOMUtil.getLastChildElement(DOMUtil.getLastChildElement(document.removeChild(document.getDocumentElement())));
			Map<String, Element> childElements = DOMUtil.getChildElementsByLocalName(fault);
			Element faultcode = childElements.get("faultcode");
			exception.getErrorContext().put("faultcode", faultcode.getTextContent());
			Element faultstring = childElements.get("faultstring");
			exception.getErrorContext().put("faultstring", faultstring.getTextContent());
			Element detail = childElements.get("detail");
			if (detail != null) {
				document.appendChild(detail);
			}
			throw exception;
		default:
			throw new ExecutionException(this, "HTTP Response Code not covered by SOAP 1.1, was " + httpResponseCode);
		}
	}

}
