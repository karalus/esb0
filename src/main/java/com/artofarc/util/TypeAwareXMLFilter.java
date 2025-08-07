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
import javax.xml.validation.TypeInfoProvider;
import javax.xml.validation.ValidatorHandler;

import org.w3c.dom.TypeInfo;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public abstract class TypeAwareXMLFilter extends XMLFilterBase {

	private final ValidatorHandler _validatorHandler;
	private final XMLFilterBase _receiver;
	private SAXParser _saxParser;
	private TypeInfo typeInfo;
	protected String _contentType;

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

	protected final boolean isXSType(String type) {
		return typeInfo != null && typeInfo.isDerivedFrom(XMLConstants.W3C_XML_SCHEMA_NS_URI, type, TypeInfo.DERIVATION_RESTRICTION | TypeInfo.DERIVATION_EXTENSION);
	}

	protected final boolean isBase64Binary() {
		return isXSType("base64Binary");
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

	protected abstract void startXOPInclude(String href) throws SAXException;

	@Override
	public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
		if (W3CConstants.isXOPInclude(uri, localName)) {
			startXOPInclude(atts.getValue(W3CConstants.NAME_HREF));
			if (getReceiverContentHandler() != null) {
				_skipTypeInfo = true;
				getReceiverContentHandler().startElement(uri, localName, qName, atts);
			}
		} else {
			_contentType = atts.getValue(W3CConstants.URI_NS_XMLMIME, W3CConstants.NAME_CONTENT_TYPE);
			super.startElement(uri, localName, qName, atts);
		}
	}

	@Override
	public void endElement(String uri, String localName, String qName) throws SAXException {
		typeInfo = null;
		_contentType = null;
		if (!W3CConstants.isXOPInclude(uri, localName)) {
			super.endElement(uri, localName, qName);
		} else if (getReceiverContentHandler() != null) {
			getReceiverContentHandler().endElement(uri, localName, qName);
			_skipTypeInfo = false;
		}
	}

	private boolean _skipTypeInfo;

	private final TypeInfoProvider _typeInfoProvider = new TypeInfoProvider() {

		@Override
		public boolean isSpecified(int index) {
			return _skipTypeInfo ? true : _validatorHandler.getTypeInfoProvider().isSpecified(index);
		}

		@Override
		public boolean isIdAttribute(int index) {
			return _skipTypeInfo ? false : _validatorHandler.getTypeInfoProvider().isIdAttribute(index);
		}

		@Override
		public TypeInfo getElementTypeInfo() {
			return _skipTypeInfo ? null : _validatorHandler.getTypeInfoProvider().getElementTypeInfo();
		}

		@Override
		public TypeInfo getAttributeTypeInfo(int index) {
			return _skipTypeInfo ? null : _validatorHandler.getTypeInfoProvider().getAttributeTypeInfo(index);
		}
	};

	public TypeInfoProvider getTypeInfoProvider() {
		return _typeInfoProvider;
	}

}
