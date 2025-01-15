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

import java.util.function.BiConsumer;

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

	static class XQJIdentityFilter<T extends ContentHandler> extends XQJFilter {
		private final T _handler;
		private final BiConsumer<T, ContentHandler> _setter;

		XQJIdentityFilter(XQItem item, T handler, BiConsumer<T, ContentHandler> setter) {
			super(item);
			super.setContentHandler(_handler = handler);
			_setter = setter;
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

		@Override
		public void setContentHandler(ContentHandler handler) {
			_setter.accept(_handler, handler);
		}
	}

	@Override
	protected SAXSource createSAXSource(Context context, ESBMessage message, XQItem item) {
		message.setSchema(_schema);
		XMLReader xmlReader = message.getAttachments().isEmpty() ? new XQJIdentityFilter<>(item, _schema.newValidatorHandler(), ValidatorHandler::setContentHandler)
				: new XQJIdentityFilter<>(item, new XopAwareValidatorHandler(_schema, message.getAttachments().keySet()), XopAwareValidatorHandler::setContentHandler);
		return new SAXSource(xmlReader, null);
	}

	@Override
	protected XMLFilterBase createXMLFilter(Context context, ESBMessage message, XMLReader parent) throws Exception {
		message.setSchema(_schema);
		if (message.getAttachments().isEmpty()) {
			return parent != null ? new ChainingXMLFilter<>(parent, _schema.newValidatorHandler(), ValidatorHandler::setContentHandler)
					: new ChainingXMLFilter<>(context.getSAXParser(), _schema.newValidatorHandler(), ValidatorHandler::setContentHandler);
		} else {
			XopAwareValidatorHandler validatorHandler = new XopAwareValidatorHandler(_schema, message.getAttachments().keySet());
			return parent != null ? new ChainingXMLFilter<>(parent, validatorHandler, XopAwareValidatorHandler::setContentHandler)
					: new ChainingXMLFilter<>(context.getSAXParser(), validatorHandler, XopAwareValidatorHandler::setContentHandler);
		}
	}

}
