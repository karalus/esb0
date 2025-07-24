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
package com.artofarc.esb.action;

import javax.xml.namespace.QName;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import static com.artofarc.esb.http.HttpConstants.*;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;

public class PostSOAPHttpAction extends UnwrapSOAPAction {

	private final QName _expectedElement;

	public PostSOAPHttpAction(boolean soap12, boolean singlePart, String expectedElement) {
		super(soap12, singlePart);
		_expectedElement = expectedElement != null ? QName.valueOf(expectedElement) : null;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		Integer httpResponseCode = message.getVariable(ESBConstants.HttpResponseCode);
		switch (httpResponseCode) {
		// SOAP 1.2 allows for more HTTP status codes. Refer to "Table 20" in https://www.w3.org/TR/soap12-part2/
		case SC_ACCEPTED:
		case SC_BAD_REQUEST:
			if (!_soap12) {
				throw new ExecutionException(this, "HTTP Response Code " + httpResponseCode + " not covered by SOAP 1.1 protocol");
			}
			// no break
		case SC_OK:
		case SC_INTERNAL_SERVER_ERROR:
			return super.prepare(context, message, inPipeline);
		default:
			throw new ExecutionException(this, "HTTP Response Code not covered by SOAP protocol, was " + httpResponseCode);
		}
	}

	@Override
	protected String validateOperation(ESBMessage message) throws ExecutionException {
		if (_expectedElement != null && !_expectedElement.equals(message.getVariable(ESBConstants.SOAP_ELEMENT_NAME))) {
			throw new ExecutionException(this, "Unexpected output element: " + message.getVariable(ESBConstants.SOAP_ELEMENT_NAME));
		}
		return null;
	}

}
