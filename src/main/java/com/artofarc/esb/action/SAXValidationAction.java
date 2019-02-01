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

import javax.xml.transform.sax.SAXSource;
import javax.xml.validation.Schema;
import javax.xml.validation.ValidatorHandler;
import javax.xml.xquery.XQItem;

import org.xml.sax.ContentHandler;
import org.xml.sax.XMLReader;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.message.ESBMessage;

public class SAXValidationAction extends SAXAction {

	private final Schema _schema;

	public SAXValidationAction(Schema schema) {
		_schema = schema;
	}

	class ValidatingXQJFilter extends XQJFilter {
		
		private final ValidatorHandler _validatorHandler = _schema.newValidatorHandler();

		ValidatingXQJFilter(XQItem item) {
			super(item);
		}

		@Override
		public ContentHandler getContentHandler() {
			_validatorHandler.setContentHandler(super.getContentHandler());
			return _validatorHandler;
		}

	}

	@Override
	protected SAXSource createSAXSource(XQItem item, Context context, ESBMessage message) {
		message.setSchema(_schema);
		return new SAXSource(new ValidatingXQJFilter(item), null);
	}

	@Override
	protected FeatureFilter createXMLFilter(XMLReader parent, Context context, ESBMessage message) {
		message.setSchema(_schema);
		FeatureFilter xmlFilter = new FeatureFilter(parent);
		xmlFilter.setContentHandler(_schema.newValidatorHandler());
		return xmlFilter;
	}

}
