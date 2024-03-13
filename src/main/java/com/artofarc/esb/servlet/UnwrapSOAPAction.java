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
package com.artofarc.esb.servlet;

import java.util.List;

import javax.wsdl.BindingOperation;
import javax.xml.validation.Schema;

import com.artofarc.esb.action.Action;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import static com.artofarc.esb.message.ESBConstants.*;
import com.artofarc.esb.message.ESBMessage;

public class UnwrapSOAPAction extends com.artofarc.esb.action.UnwrapSOAPAction {

	private final String _wsdlUrl;

	public UnwrapSOAPAction(boolean soap12, boolean singlePart, Schema schema, List<BindingOperation> bindingOperations, String wsdlUrl) {
		super(soap12, singlePart, schema, bindingOperations);
		_wsdlUrl = wsdlUrl;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		if ("GET".equals(message.getVariable(HttpMethod))) {
			String queryString = message.getVariable(QueryString);
			if ("wsdl".equals(queryString) || "WSDL".equals(queryString)) {
				message.getVariables().put(redirect, message.getVariable(ContextPath) + ESBServletContextListener.ADMIN_SERVLET_PATH + _wsdlUrl);
				return null;
			}
		}
		return super.prepare(context, message, inPipeline);
	}

	@Override
	protected Action nextAction(ExecutionContext execContext) {
		return execContext != null ? super.nextAction(execContext) : null;
	}

}
