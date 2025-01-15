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
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.XMLFilterBase;
import com.artofarc.util.XopAwareValidatorHandler;

public class SAXValidationAction extends SAXAction {

	private final Schema _schema;

	public SAXValidationAction(Schema schema) {
		_schema = schema;
	}

	abstract static class XQJIdentityFilter extends XQJFilter {
		XQJIdentityFilter(XQItem item) {
			super(item);
		}

		@Override
		public Object getProperty(String name) {
			if (name == ESBConstants.xqItem) {
				try {
					parse((InputSource) null);
				} catch (SAXException e) {
					throw new RuntimeException(e);
				}
				return _item;
			}
			return null;
		}
	}

	class ValidatingXQJFilter extends XQJIdentityFilter {
		private final ValidatorHandler _validatorHandler = _schema.newValidatorHandler();

		ValidatingXQJFilter(XQItem item) {
			super(item);
			super.setContentHandler(_validatorHandler);
		}

		@Override
		public void setContentHandler(ContentHandler handler) {
			_validatorHandler.setContentHandler(handler);
		}
	}

	static class XopAwareValidatingXQJFilter extends XQJIdentityFilter {
		private final XopAwareValidatorHandler _validatorHandler;

		XopAwareValidatingXQJFilter(XQItem item, XopAwareValidatorHandler validatorHandler) {
			super(item);
			super.setContentHandler(_validatorHandler = validatorHandler);
		}

		@Override
		public void setContentHandler(ContentHandler handler) {
			_validatorHandler.setContentHandler(handler);
		}
	}

	class ValidatingReuseParserXMLFilter extends ReuseParserXMLFilter {
		private final ValidatorHandler _validatorHandler = _schema.newValidatorHandler();

		ValidatingReuseParserXMLFilter(SAXParser saxParser) throws SAXException {
			super(saxParser);
			super.setContentHandler(_validatorHandler);
		}

		@Override
		public void setContentHandler(ContentHandler handler) {
			_validatorHandler.setContentHandler(handler);
		}
	}

	static class XopAwareValidatingReuseParserXMLFilter extends ReuseParserXMLFilter {
		private final XopAwareValidatorHandler _validatorHandler;

		XopAwareValidatingReuseParserXMLFilter(SAXParser saxParser, XopAwareValidatorHandler validatorHandler) throws SAXException {
			super(saxParser);
			super.setContentHandler(validatorHandler);
			_validatorHandler = validatorHandler;
		}

		@Override
		public void setContentHandler(ContentHandler handler) {
			_validatorHandler.setContentHandler(handler);
		}
	}

	class ValidatingXMLFilter extends XMLFilterBase {
		private final ValidatorHandler _validatorHandler = _schema.newValidatorHandler();

		ValidatingXMLFilter(XMLReader parent) {
			super(parent);
			parent.setContentHandler(_validatorHandler);
		}

		@Override
		public void setContentHandler(ContentHandler handler) {
			_validatorHandler.setContentHandler(handler);
		}

		@Override
		public void parse(InputSource source) throws SAXException, IOException {
			getParent().parse(source);
		}
	}

	static class XopAwareValidatingXMLFilter extends XMLFilterBase {
		private final XopAwareValidatorHandler _validatorHandler;

		XopAwareValidatingXMLFilter(XMLReader parent, XopAwareValidatorHandler validatorHandler) {
			super(parent);
			parent.setContentHandler(validatorHandler);
			_validatorHandler = validatorHandler;
		}

		@Override
		public void setContentHandler(ContentHandler handler) {
			_validatorHandler.setContentHandler(handler);
		}

		@Override
		public void parse(InputSource source) throws SAXException, IOException {
			getParent().parse(source);
		}
	}

	@Override
	protected SAXSource createSAXSource(Context context, ESBMessage message, XQItem item) {
		message.setSchema(_schema);
		XMLReader xmlReader = message.getAttachments().isEmpty() ? new ValidatingXQJFilter(item) : new XopAwareValidatingXQJFilter(item, new XopAwareValidatorHandler(_schema, message.getAttachments().keySet()));
		return new SAXSource(xmlReader, null);
	}

	@Override
	protected XMLFilterBase createXMLFilter(Context context, ESBMessage message, XMLReader parent) throws Exception {
		message.setSchema(_schema);
		if (message.getAttachments().isEmpty()) {
			return parent != null ? new ValidatingXMLFilter(parent) : new ValidatingReuseParserXMLFilter(context.getSAXParser());
		} else {
			XopAwareValidatorHandler validatorHandler = new XopAwareValidatorHandler(_schema, message.getAttachments().keySet());
			return parent != null ? new XopAwareValidatingXMLFilter(parent, validatorHandler) : new XopAwareValidatingReuseParserXMLFilter(context.getSAXParser(), validatorHandler);
		}
	}

}
