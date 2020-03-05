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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.wsdl.BindingOperation;
import javax.xml.namespace.QName;
import javax.xml.validation.Schema;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import static com.artofarc.esb.http.HttpConstants.*;

import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.servlet.ESBServletContextListener;

import static com.artofarc.esb.message.ESBConstants.*;
import com.artofarc.util.Collections;
import com.artofarc.util.WSDL4JUtil;

public class UnwrapSOAPAction extends TransformAction {

	protected final boolean _soap12;
	protected final Map<String, String> _mapAction2Operation;
	protected final Map<String, QName> _operations;
	private final String _wsdlUrl;
	private final boolean _getWsdl;
	private final Schema _schema;

	/**
	 * @param singlePart whether body can contain more than one element.
	 *
	 * @see <a href="https://www.ibm.com/developerworks/webservices/library/ws-whichwsdl/">WSDL styles/</a>
	 */
	private UnwrapSOAPAction(boolean soap12, boolean singlePart, Map<String, String> mapAction2Operation, List<BindingOperation> bindingOperations, String wsdlUrl, boolean getWsdl, Schema schema) {
		super("declare namespace soapenv=\"" + (soap12 ? URI_NS_SOAP_1_2_ENVELOPE : URI_NS_SOAP_1_1_ENVELOPE ) + "\";\n"
				+ "let $h := soapenv:Envelope[1]/soapenv:Header[1] let $b := soapenv:Envelope[1]/soapenv:Body[1]" + (singlePart ? "/*[1]" : "") + " return ("
				+ (singlePart && bindingOperations != null ? "local-name($b), " : "") + "if ($h) then (true(), $h) else false(), $b)",
				singlePart && bindingOperations != null ?
						Arrays.asList(new Assignment(SOAP_OPERATION, false, null, false, null), new Assignment(SOAP_HEADER, false, null, true, null)) :
						java.util.Collections.singletonList(new Assignment(SOAP_HEADER, false, null, true, null)));
		
		_soap12 = soap12;
		_mapAction2Operation = mapAction2Operation;
		if (singlePart && bindingOperations != null) {
			_operations = new HashMap<>();
			for (BindingOperation bindingOperation : bindingOperations) {
				_operations.put(bindingOperation.getName(), WSDL4JUtil.getInputElementQName(bindingOperation, soap12));
			}
		} else {
			_operations = null;
		}
		_wsdlUrl = wsdlUrl;
		_getWsdl = getWsdl;
		_schema = schema;
	}

	public UnwrapSOAPAction(boolean soap12, boolean singlePart, Schema schema, List<BindingOperation> bindingOperations, String wsdlUrl, boolean getWsdl) {
		this(soap12, singlePart, Collections.inverseMap(WSDL4JUtil.getMapOperation2SoapActionURI(bindingOperations).entrySet(), false), bindingOperations, wsdlUrl, getWsdl, schema);
	}

	public UnwrapSOAPAction(boolean soap12, boolean singlePart) {
		this(soap12, singlePart, java.util.Collections.<String, String> emptyMap(), null, null, false, null);
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		if ("GET".equals(message.getVariable(HttpMethod))) {
			String queryString = message.getVariable(QueryString);
			if (_getWsdl && ("wsdl".equals(queryString) || "WSDL".equals(queryString))) {
				message.getVariables().put(redirect, message.getVariable(ContextPath) + "/" + ESBServletContextListener.ADMIN_SERVLET_PATH + _wsdlUrl);
				return null;
			} else if (!_soap12) {
				throw new ExecutionException(this, "HTTP method not allowed: " + message.getVariable(HttpMethod));
			}
		}
		String type = parseContentType(message.<String> getHeader(HTTP_HEADER_CONTENT_TYPE));
		if (!_soap12 && !isSOAP11(type) || _soap12 && !isSOAP12(type)) {
			String error = "Unexpected Content-Type: " + type;
			if (message.getBodyType() != BodyType.INVALID) {
				error += "\n" + message.getBodyAsString(context);
			}
			throw new ExecutionException(this, error);
		}
		ExecutionContext execContext = super.prepare(context, message, inPipeline);
		message.putVariable(SOAP_OPERATION, determineOperation(message));
		if (_schema != null) {
			message.setSchema(_schema);
		}
		return execContext;
	}

	protected String determineOperation(ESBMessage message) throws ExecutionException {
		String soapAction = message.getHeader(HTTP_HEADER_SOAP_ACTION);
		if (soapAction == null && _soap12) {
			String contentType = message.getHeader(HTTP_HEADER_CONTENT_TYPE);
			soapAction = getValueFromHttpHeader(contentType, HTTP_HEADER_CONTENT_TYPE_PARAMETER_ACTION);
		}
		if (soapAction != null) {
			// soapAction is always embedded in quotes
			String operation = _mapAction2Operation.get(removeQuotes(soapAction));
			if (operation != null) {
				return operation;
			}
		}
		if (_operations != null) {
			String inputElementName = message.getVariable(SOAP_OPERATION);
			if (_operations.containsKey(inputElementName)) {
				return inputElementName;
			} else {
				if (_operations.size() == 1) {
					return _operations.keySet().iterator().next();
				} else {
					for (Map.Entry<String, QName> entry : _operations.entrySet()) {
						if (entry.getValue() != null && inputElementName.equals(entry.getValue().getLocalPart())) {
							return entry.getKey();
						}
					}
				}
			}
			throw new ExecutionException(this, "Operation not found in WSDL. Input element: " + inputElementName);
		}
		throw new ExecutionException(this, "Operation not found in WSDL. SOAP action: " + soapAction);
	}

	@Override
	protected Action nextAction(ExecutionContext execContext) {
		return execContext != null ? super.nextAction(execContext) : null;
	}

}
