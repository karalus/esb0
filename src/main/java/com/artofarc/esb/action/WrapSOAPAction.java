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

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import static com.artofarc.esb.http.HttpConstants.*;
import com.artofarc.esb.message.ESBMessage;

public class WrapSOAPAction extends TransformAction {

	protected final boolean _soap12;
	
	public WrapSOAPAction(boolean soap12, boolean header, boolean singlePart) {
		super("declare variable $header as element() external; <soapenv:Envelope xmlns:soapenv=\""
				+ (soap12 ? URI_NS_SOAP_1_2_ENVELOPE : URI_NS_SOAP_1_1_ENVELOPE) + "\">"
				+ (header ? "{$header}" : "")
				+ (singlePart ? "<soapenv:Body>" : "") + "{.}" + (singlePart ? "</soapenv:Body>" : "") + "</soapenv:Envelope>");
		_soap12 = soap12;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		message.getHeaders().clear();
		message.getHeaders().put(HTTP_HEADER_CONTENT_TYPE, _soap12 ? SOAP_1_2_CONTENT_TYPE : SOAP_1_1_CONTENT_TYPE);
		return super.prepare(context, message, inPipeline);
	}

}
