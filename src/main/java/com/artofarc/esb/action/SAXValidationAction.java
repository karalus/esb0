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
import javax.xml.validation.TypeInfoProvider;
import javax.xml.validation.ValidatorHandler;
import javax.xml.xquery.XQItem;

import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.RichSource;
import com.artofarc.util.XMLFilterBase;
import com.artofarc.util.XopAwareValidatorHandler;

public class SAXValidationAction extends SAXAction {

	private final Schema _schema;

	public SAXValidationAction(Schema schema) {
		_schema = schema;
	}

	static class XQJIdentityFilter<T extends ContentHandler> extends XQJFilter implements RichSource.XQItemSupplier {
		private final T _handler;
		private final BiConsumer<T, ContentHandler> _setter;

		XQJIdentityFilter(XQItem item, T handler, BiConsumer<T, ContentHandler> setter) {
			super(item);
			super.setContentHandler(_handler = handler);
			_setter = setter;
		}

		@Override
		public XQItem getXQItem() throws SAXException {
			parse((InputSource) null);
			return _item;
		}

		@Override
		public void setContentHandler(ContentHandler handler) {
			_setter.accept(_handler, handler);
		}
	}

	@Override
	protected RichSource createSource(Context context, ESBMessage message, XQItem item) throws Exception {
		Schema schema;
		if (_schema != null) {
			message.setSchema(schema = _schema);
		} else if ((schema = message.getSchema()) == null) {
			throw new ExecutionException(this, "No schema set");
		}
		message.setSchema(schema);
		TypeInfoProvider typeInfoProvider;
		XQJIdentityFilter<?> xmlFilter;
		if (message.getAttachments().isEmpty()) {
			ValidatorHandler validatorHandler = schema.newValidatorHandler();
			typeInfoProvider = validatorHandler.getTypeInfoProvider();
			xmlFilter = new XQJIdentityFilter<>(item, validatorHandler, ValidatorHandler::setContentHandler);
		} else {
			XopAwareValidatorHandler validatorHandler = message.createXopAwareValidatorHandler(schema);
			typeInfoProvider = validatorHandler.getTypeInfoProvider();
			xmlFilter = new XQJIdentityFilter<>(item, validatorHandler, XopAwareValidatorHandler::setContentHandler);
		}
		return new RichSource(new SAXSource(xmlFilter, null), item, xmlFilter, typeInfoProvider);
	}

	@Override
	protected RichSource createSource(Context context, ESBMessage message, XMLReader parent, InputSource inputSource, boolean xqItemKindElement) throws Exception {
		Schema schema;
		if (_schema != null) {
			message.setSchema(schema = _schema);
		} else if ((schema = message.getSchema()) == null) {
			throw new ExecutionException(this, "No schema set");
		}
		TypeInfoProvider typeInfoProvider;
		XMLFilterBase xmlFilter;
		if (message.getAttachments().isEmpty()) {
			ValidatorHandler validatorHandler = schema.newValidatorHandler();
			typeInfoProvider = validatorHandler.getTypeInfoProvider();
			xmlFilter = parent != null ? new ChainingXMLFilter<>(parent, validatorHandler, ValidatorHandler::setContentHandler)
					: new ChainingXMLFilter<>(context.getSAXParser(), validatorHandler, ValidatorHandler::setContentHandler);
		} else {
			XopAwareValidatorHandler validatorHandler = message.createXopAwareValidatorHandler(schema);
			typeInfoProvider = validatorHandler.getTypeInfoProvider();
			xmlFilter = parent != null ? new ChainingXMLFilter<>(parent, validatorHandler, XopAwareValidatorHandler::setContentHandler)
					: new ChainingXMLFilter<>(context.getSAXParser(), validatorHandler, XopAwareValidatorHandler::setContentHandler);
		}
		return new RichSource(new SAXSource(xmlFilter, inputSource), xqItemKindElement, typeInfoProvider);
	}

}
