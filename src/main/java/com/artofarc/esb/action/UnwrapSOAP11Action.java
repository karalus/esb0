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
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import javax.wsdl.BindingOperation;
import javax.wsdl.Definition;
import javax.xml.soap.SOAPConstants;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.ESBVariableConstants;
import com.artofarc.util.Collections;
import com.artofarc.util.WSDL4JUtil;

public class UnwrapSOAP11Action extends TransformAction {

	private final Map<String, String> _mapAction2Operation;

	private final HashSet<String> _operations;

	/**
	 * Cannot decompose further since body can contain more than one element.
	 * 
	 * @see https://www.ibm.com/developerworks/webservices/library/ws-whichwsdl/
	 */
	private UnwrapSOAP11Action(Map<String, String> mapAction2Operation, List<BindingOperation> bindingOperations) {
		super(
				"declare namespace soapenv=\""
						+ SOAPConstants.URI_NS_SOAP_1_1_ENVELOPE
						+ "\";\n"
						+ "let $h := soapenv:Envelope/soapenv:Header let $b := soapenv:Envelope/soapenv:Body return (local-name($b/*[1]), if ($h) then $h else <soapenv:Header/>, if ($b) then $b else .)",
				Arrays.asList(ESBVariableConstants.operation, ESBVariableConstants.SOAP_HEADER));

		_mapAction2Operation = mapAction2Operation;
		if (bindingOperations != null) {
			_operations = new HashSet<>();
			for (BindingOperation bindingOperation : bindingOperations) {
				_operations.add(bindingOperation.getName());
			}
		} else {
			_operations = null;
		}
	}

	public UnwrapSOAP11Action(Definition definition, String transport) {
		this(Collections.inverseMap(WSDL4JUtil.getMapOperation2SoapActionURI(definition, transport)), WSDL4JUtil.getBindingOperations(definition, transport));
	}

	public UnwrapSOAP11Action() {
		this(java.util.Collections.<String, String> emptyMap(), null);
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		String contentType = message.getHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE);
		if (contentType == null || !contentType.contains(SOAPConstants.SOAP_1_1_CONTENT_TYPE)) {
			throw new ExecutionException(this, "Unexpected Content-Type: " + contentType);
		}
		String soapAction = message.getHeader(HttpOutboundAction.HTTP_HEADER_SOAP_ACTION);
		ExecutionContext execContext = super.prepare(context, message, inPipeline);
		if (soapAction != null) {
			// soapAction is always embedded in quotes
			soapAction = soapAction.substring(1, soapAction.length() - 1);
			String operation = _mapAction2Operation.get(soapAction);
			if (operation != null) {
				message.getVariables().put(ESBVariableConstants.operation, operation);
			}
		}
		String operation = message.getVariable(ESBVariableConstants.operation);
		if (_operations != null && !_operations.contains(operation)) {
			throw new ExecutionException(this, "Operation not found in WSDL: " + operation);
		}
		return execContext;
	}

}
