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

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import javax.xml.transform.sax.SAXResult;
import javax.xml.validation.Schema;
import javax.xml.xquery.XQException;
import javax.xml.xquery.XQResultSequence;

import com.artofarc.esb.message.ESBMessage;

public class ValidateAction extends AssignAction {

	private final Schema _schema;

	public ValidateAction(Schema schema, String expression, Collection<Map.Entry<String, String>> namespaces, String contextItem) {
		super(Collections.singletonList(new Assignment(null, false, expression, null, null)), null, namespaces, Collections.emptyList(), null, contextItem, false);
		_schema = schema;
	}

	@Override
	protected void processSequence(ESBMessage message, XQResultSequence resultSequence) throws ExecutionException {
		Schema schema = _schema != null ? _schema : message.getSchema();
		if (schema == null) {
			throw new ExecutionException(this, "No schema set");
		}
		try {
			checkNext(resultSequence, "expression");
			resultSequence.writeItemToResult(new SAXResult(message.getAttachments().isEmpty() ? schema.newValidatorHandler() : message.createXopAwareValidatorHandler(schema)));
		} catch (XQException e) {
			throw new ExecutionException(this, "Validation failed", e);
		}
		if (_contextItem == null) {
			message.setSchema(schema);
		}
	}

}
