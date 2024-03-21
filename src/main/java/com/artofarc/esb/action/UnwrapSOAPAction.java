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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.wsdl.BindingOperation;
import javax.xml.namespace.QName;
import javax.xml.validation.Schema;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import static com.artofarc.esb.http.HttpConstants.*;
import com.artofarc.esb.message.BodyType;
import static com.artofarc.esb.message.ESBConstants.*;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.DataStructures;
import static com.artofarc.util.W3CConstants.*;
import static com.artofarc.util.WSDL4JUtil.*;

public class UnwrapSOAPAction extends TransformAction {

	protected final boolean _soap12;
	protected final Map<String, String> _mapAction2Operation;
	protected final Map<String, QName> _operations;
	private final Schema _schema;

	private static final List<Assignment> ARG2 = Arrays.asList(new Assignment(SOAP_HEADER, true), new Assignment(SOAP_ELEMENT_NAME, false));
	private static final List<Assignment> ARG1 = Collections.singletonList(new Assignment(SOAP_HEADER, true));

	/**
	 * @param singlePart whether body cannot contain more than one element.
	 *
	 * @see <a href="https://www.ibm.com/developerworks/webservices/library/ws-whichwsdl/">WSDL styles/</a>
	 */
	private UnwrapSOAPAction(boolean soap12, boolean singlePart, Map<String, String> mapAction2Operation, List<BindingOperation> bindingOperations, Schema schema) {
		super("declare namespace soapenv=\"" + (soap12 ? URI_NS_SOAP_1_2_ENVELOPE : URI_NS_SOAP_1_1_ENVELOPE ) + "\";\n"
				+ "let $h := soapenv:Envelope[1]/soapenv:Header[1] let $b := soapenv:Envelope[1]/soapenv:Body[1]" + (singlePart ? "/*[1]" : "") + " return (count($h), $h, "
				+ (singlePart && bindingOperations != null ? "node-name($b), " : "") + "$b)", singlePart && bindingOperations != null ? ARG2 : ARG1, HTTP_HEADER_CONTENT_TYPE_XML);
		
		_soap12 = soap12;
		_mapAction2Operation = mapAction2Operation;
		if (singlePart && bindingOperations != null) {
			_operations = DataStructures.createHashMap(bindingOperations.size());
			for (BindingOperation bindingOperation : bindingOperations) {
				_operations.put(bindingOperation.getName(), getInputElementQName(bindingOperation, soap12));
			}
		} else {
			_operations = null;
		}
		_schema = schema;
	}

	public UnwrapSOAPAction(boolean soap12, boolean singlePart, Schema schema, List<BindingOperation> bindingOperations) {
		this(soap12, singlePart, DataStructures.inverseMap(getMapOperation2SoapActionURI(bindingOperations)), bindingOperations, schema);
	}

	public UnwrapSOAPAction(boolean soap12, boolean singlePart) {
		this(soap12, singlePart, Collections.emptyMap(), null, null);
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		String method = message.getVariable(HttpMethod);
		if (method != null && !("POST".equals(method) || _soap12 && "GET".equals(method))) {
			throw new ExecutionException(this, "HTTP method not allowed: " + method);
		}
		String type = parseContentType(message.getContentType());
		if (!_soap12 && isNotSOAP11(type) || _soap12 && isNotSOAP12(type)) {
			String error = "Unexpected Content-Type: " + type;
			if (message.getBodyType() != BodyType.INVALID) {
				error += "\n" + message.getBodyAsString(context);
			}
			throw new ExecutionException(this, error);
		}
		ExecutionContext execContext = super.prepare(context, message, inPipeline);
		message.putVariableIfNotNull(SOAP_OPERATION, determineOperation(message));
		if (_schema != null) {
			message.setSchema(_schema);
		}
		return execContext;
	}

	protected String determineOperation(ESBMessage message) throws ExecutionException {
		String soapAction = message.getHeader(HTTP_HEADER_SOAP_ACTION);
		if (soapAction != null) {
			soapAction = soapAction.isEmpty() ? null : soapAction.substring(1, soapAction.length() - 1);
		} else if (_soap12) {
			soapAction = getValueFromHttpHeader(message.getContentType(), HTTP_HEADER_CONTENT_TYPE_PARAMETER_ACTION);
		}
		if (soapAction != null) {
			String operation = _mapAction2Operation.get(soapAction);
			if (operation != null) {
				return operation;
			}
		}
		if (_operations != null) {
			QName inputElementName = message.getVariable(SOAP_ELEMENT_NAME);
			if (_operations.containsKey(inputElementName.getLocalPart())) {
				// document literal wrapped style
				return inputElementName.getLocalPart();
			}
			if (_operations.size() == 1) {
				// TOREVIEW: Fast but not validating if it really matches
				return _operations.keySet().iterator().next();
			}
			for (Map.Entry<String, QName> entry : _operations.entrySet()) {
				if (inputElementName.equals(entry.getValue())) {
					return entry.getKey();
				}
			}
			throw new ExecutionException(this, "Operation not found in WSDL. Input element: " + inputElementName);
		}
		throw new ExecutionException(this, "Operation not found in WSDL. SOAP action: " + soapAction);
	}

}
