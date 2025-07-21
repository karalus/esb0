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
package com.artofarc.util;

import java.io.IOException;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParser;
import javax.xml.validation.Schema;
import javax.xml.validation.ValidatorHandler;

import org.w3c.dom.TypeInfo;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public abstract class TypeAwareXMLFilter extends XMLFilterBase {

	protected final ValidatorHandler _validatorHandler;
	private final XMLFilterBase _receiver;
	private SAXParser _saxParser;
	protected TypeInfo typeInfo;

	public TypeAwareXMLFilter(Schema schema) {
		_validatorHandler = schema.newValidatorHandler();
		super.setContentHandler(_validatorHandler);
		_receiver = new XMLFilterBase() {

			@Override
			public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
				typeInfo = _validatorHandler.getTypeInfoProvider().getElementTypeInfo();
				super.startElement(uri, localName, qName, atts);
			}
		};
		_validatorHandler.setContentHandler(_receiver);
	}

	protected boolean isXSType(String type) {
		return typeInfo.isDerivedFrom(XMLConstants.W3C_XML_SCHEMA_NS_URI, type, TypeInfo.DERIVATION_RESTRICTION | TypeInfo.DERIVATION_EXTENSION);
	}

	public final ContentHandler getReceiverContentHandler() {
		return _receiver.getContentHandler();
	}

	@Override
	public final void setContentHandler(ContentHandler handler) {
		_receiver.setContentHandler(handler);
	}

	@Override
	public final void setErrorHandler(ErrorHandler handler) {
		_validatorHandler.setErrorHandler(handler);
	}

	@Override
	public final ErrorHandler getErrorHandler() {
		return _validatorHandler.getErrorHandler();
	}

	public final void setParent(SAXParser saxParser) throws SAXException {
		_saxParser = saxParser;
		setParent(saxParser.getXMLReader());
	}

	@Override
	public final void parse(InputSource input) throws SAXException, IOException {
		try {
			super.parse(input);
		} finally {
			if (_saxParser != null) {
				_saxParser.reset();
			}
		}
	}

}
