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

import javax.wsdl.Binding;
import javax.wsdl.BindingOperation;
import javax.xml.namespace.QName;
import javax.xml.validation.Schema;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import static com.artofarc.esb.http.HttpConstants.*;

import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.servlet.DeployServlet;

import static com.artofarc.esb.message.ESBConstants.*;
import com.artofarc.util.Collections;
import com.artofarc.util.WSDL4JUtil;

public class UnwrapSOAPAction extends TransformAction {

	private final boolean _soap12;
	private final Map<String, String> _mapAction2Operation;
	private final HashSet<String> _operations;
	private final String _wsdlUrl;
	private final Schema _schema;

	/**
	 * @param singlePart whether body can contain more than one element.
	 *
	 * @see <a href="https://www.ibm.com/developerworks/webservices/library/ws-whichwsdl/">WSDL styles/</a>
	 */
	private UnwrapSOAPAction(boolean soap12, boolean singlePart, Map<String, String> mapAction2Operation, List<BindingOperation> bindingOperations, String wsdlUrl, Schema schema) {
		super("declare namespace soapenv=\"" + (soap12 ? URI_NS_SOAP_1_2_ENVELOPE : URI_NS_SOAP_1_1_ENVELOPE ) + "\";\n"
				+ "let $h := soapenv:Envelope[1]/soapenv:Header[1] let $b := soapenv:Envelope[1]/soapenv:Body[1]" + (singlePart ? "/*[1]" : "") + " return ("
				+ (singlePart && bindingOperations != null ? "local-name($b), " : "") + "if ($h) then $h else <soapenv:Header/>, $b)",
				singlePart && bindingOperations != null ? Arrays.asList(SOAP_OPERATION, SOAP_HEADER) : java.util.Collections.singletonList(SOAP_HEADER));
		
		_soap12 = soap12;
		_mapAction2Operation = mapAction2Operation;
		if (bindingOperations != null) {
			_operations = new HashSet<>();
			for (BindingOperation bindingOperation : bindingOperations) {
				_operations.add(bindingOperation.getName());
			}
		} else {
			_operations = null;
		}
		_wsdlUrl = wsdlUrl;
		_schema = schema;
	}

	public UnwrapSOAPAction(boolean soap12, boolean singlePart, Schema schema, Map<QName, Binding> allBindings, String transport, String wsdlUrl) {
		this(soap12, singlePart, Collections.inverseMap(WSDL4JUtil.getMapOperation2SoapActionURI(allBindings, transport)), WSDL4JUtil.getBindingOperations(allBindings, transport), wsdlUrl, schema);
	}

	public UnwrapSOAPAction(boolean soap12, boolean singlePart) {
		this(soap12, singlePart, java.util.Collections.<String, String> emptyMap(), null, null, null);
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		if ("GET".equals(message.getVariable(HttpMethod))) {
			if (_wsdlUrl != null && "wsdl".equals(message.getVariable(QueryString))) {
				message.getVariables().put(redirect, message.<String> getVariable(ContextPath) + DeployServlet.SERVLET_PATH + _wsdlUrl);
				return null;
			} else if (!_soap12) {
				throw new ExecutionException(this, "HTTP method not allowed: " + message.getVariable(HttpMethod));
			}
		}
		String contentType = message.getHeader(HTTP_HEADER_CONTENT_TYPE);
		if (contentType == null || !(_soap12 ? HTTP_HEADER_CONTENT_TYPE_FI_SOAP12.equals(contentType) : HTTP_HEADER_CONTENT_TYPE_FI_SOAP11.equals(contentType))
				&& !contentType.contains(_soap12 ? SOAP_1_2_CONTENT_TYPE : SOAP_1_1_CONTENT_TYPE)) {

			String error = "Unexpected Content-Type: " + contentType;
			if (message.getBodyType() != BodyType.INVALID) {
				error += "\n" + message.getBodyAsString(context);
			}
			throw new ExecutionException(this, error);
		}
		String soapAction = _soap12 ? getValueFromHttpHeader(contentType, HTTP_HEADER_CONTENT_TYPE_PARAMETER_ACTION) : message.<String> getHeader(HTTP_HEADER_SOAP_ACTION);
		ExecutionContext execContext = super.prepare(context, message, inPipeline);
		if (soapAction != null) {
			// soapAction is always embedded in quotes
			soapAction = soapAction.substring(1, soapAction.length() - 1);
			String operation = _mapAction2Operation.get(soapAction);
			if (operation != null) {
				message.getVariables().put(SOAP_OPERATION, operation);
			}
		}
		String operation = message.getVariable(SOAP_OPERATION);
		if (_operations != null && !_operations.contains(operation)) {
			throw new ExecutionException(this, "Operation not found in WSDL: " + operation);
		}
		if (_schema != null) {
			message.setSchema(_schema);
		}
		return execContext;
	}

	@Override
	protected Action nextAction(ExecutionContext execContext) {
		return execContext != null ? super.nextAction(execContext) : null;
	}

}
