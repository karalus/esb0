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

import java.util.Map;

import javax.wsdl.Binding;
import javax.xml.validation.Schema;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import static com.artofarc.esb.http.HttpConstants.*;

import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.util.WSDL4JUtil;

public class PreSOAPHttpAction extends WrapSOAPAction {

	private final Map<String, String> _mapOperation2SoapActionURI;
	private final Schema _schema;

	@SuppressWarnings("unchecked")
	public PreSOAPHttpAction(boolean soap12, boolean header, boolean singlePart, Schema schema, Binding binding) {
		super(soap12, header, singlePart, binding != null && WSDL4JUtil.isSOAPBindingRPCStyle(binding) ? binding.getQName().getNamespaceURI() : null);
		_schema = schema;
		_mapOperation2SoapActionURI = binding != null ? WSDL4JUtil.getMapOperation2SoapActionURI(binding.getBindingOperations()) : java.util.Collections.<String, String> emptyMap();
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		ExecutionContext executionContext = super.prepare(context, message, inPipeline);
		String soapAction = _mapOperation2SoapActionURI.get(message.<String> getVariable(ESBConstants.SOAP_OPERATION));
		if (_soap12) {
			if (soapAction != null && soapAction.length() > 0) {
				String contentType = message.getHeader(HTTP_HEADER_CONTENT_TYPE);
				message.putHeader(HTTP_HEADER_CONTENT_TYPE, contentType + ';' + HTTP_HEADER_CONTENT_TYPE_PARAMETER_ACTION + soapAction);
			}
			message.putHeader(HTTP_HEADER_ACCEPT, SOAP_1_2_CONTENT_TYPE);
		} else {
			message.putHeader(HTTP_HEADER_SOAP_ACTION, soapAction != null ? '"' + soapAction + '"' : "\"\"");
			message.putHeader(HTTP_HEADER_ACCEPT, SOAP_1_1_CONTENT_TYPE);
		}
		message.getVariables().put(ESBConstants.HttpMethod, "POST");
		message.setSchema(_schema);
		return executionContext;
	}

}
