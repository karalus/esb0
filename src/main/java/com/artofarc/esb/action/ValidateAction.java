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

import org.xml.sax.ContentHandler;

import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.service.XQDecl;
import com.artofarc.util.DataStructures;
import com.artofarc.util.XopAwareValidatorHandler;

public class ValidateAction extends AssignAction {

	private final Schema _schema;

	public ValidateAction(Schema schema, String expression, Collection<Map.Entry<String, String>> namespaces, String contextItem) {
		super(null, expression, namespaces, Collections.<XQDecl>emptyList(), contextItem);
		_schema = schema;
	}

	@Override
	protected void processSequence(ESBMessage message, XQResultSequence resultSequence) throws ExecutionException {
		try {
			checkNext(resultSequence, "expression");
			ContentHandler saxhdlr = message.getAttachments().isEmpty() ? _schema.newValidatorHandler() : new XopAwareValidatorHandler(_schema, message.getAttachments().keySet());
			resultSequence.writeItemToResult(new SAXResult(saxhdlr));
		} catch (XQException e) {
			throw new ExecutionException(this, "Validation failed", DataStructures.unwrap(e));
		}
		if (_contextItem == null) {
			message.setSchema(_schema);
		}
	}

}
