/*
 * Copyright 2021 Andre Karalus
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

import java.io.IOException;

import javax.xml.parsers.SAXParser;
import javax.xml.transform.sax.SAXSource;
import javax.xml.validation.Schema;
import javax.xml.validation.ValidatorHandler;
import javax.xml.xquery.XQItem;

import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.XMLFilterBase;

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

	class ReuseParserXMLFilter extends XMLFilterBase {
		private final ValidatorHandler _validatorHandler = _schema.newValidatorHandler();
		private final SAXParser _saxParser;

		public ReuseParserXMLFilter(SAXParser saxParser) throws SAXException {
			super(saxParser.getXMLReader());
			_saxParser = saxParser;
			super.setContentHandler(_validatorHandler);
		}

		@Override
		public void parse(InputSource input) throws SAXException, IOException {
			try {
				super.parse(input);
			} finally {
				_saxParser.reset();
			}
		}

		@Override
		public void setContentHandler(ContentHandler handler) {
			_validatorHandler.setContentHandler(handler);
		}
	}

	@Override
	protected SAXSource createSAXSource(Context context, ESBMessage message, XQItem item) {
		message.setSchema(_schema);
		return new SAXSource(new ValidatingXQJFilter(item), null);
	}

	@Override
	protected XMLFilterBase createXMLFilter(Context context, ESBMessage message, XMLReader parent) throws Exception {
		message.setSchema(_schema);
		if (parent != null) {
			XMLFilterBase xmlFilter = new XMLFilterBase(parent);
			xmlFilter.setContentHandler(_schema.newValidatorHandler());
			return xmlFilter;
		}
		return new ReuseParserXMLFilter(context.getSAXParser());
	}

}
