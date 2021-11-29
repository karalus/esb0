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
package com.artofarc.util;

import java.io.IOException;
import java.io.OutputStream;

import javax.xml.XMLConstants;
import javax.xml.validation.Schema;
import javax.xml.validation.ValidatorHandler;

import org.jvnet.fastinfoset.EncodingAlgorithmIndexes;
import org.jvnet.fastinfoset.FastInfosetSerializer;
import org.jvnet.fastinfoset.RestrictedAlphabet;
import org.jvnet.fastinfoset.sax.helpers.EncodingAlgorithmAttributesImpl;
import org.w3c.dom.TypeInfo;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.XMLFilterImpl;

import com.sun.xml.fastinfoset.algorithm.BuiltInEncodingAlgorithmFactory;
import com.sun.xml.fastinfoset.sax.SAXDocumentSerializer;

public final class SchemaAwareFastInfosetSerializer extends XMLFilterImpl implements AutoCloseable {

	static boolean notContains(char[] ch, int start, int length, char c) {
		for (int i = 0; i < length; ++i) {
			if (ch[start + i] == c) return false;
		}
		return true;
	}

	private final SAXDocumentSerializer saxDocumentSerializer = new SAXDocumentSerializer();
	private OutputStream outputStream;

	public SchemaAwareFastInfosetSerializer(Schema schema, boolean ignoreWhitespace) {
		if (schema != null) {
			ValidatorHandler validatorHandler = schema.newValidatorHandler();
			validatorHandler.setContentHandler(new TypeInfoContentHandler(validatorHandler, !ignoreWhitespace));
			setContentHandler(validatorHandler);
		} else {
			setContentHandler(saxDocumentSerializer);
		}
	}

	@Override
	public void close() {
	}

	public FastInfosetSerializer getFastInfosetSerializer() {
		return saxDocumentSerializer;
	}

	public ContentHandler getContentHandler(OutputStream os, String charsetName) {
		saxDocumentSerializer.setOutputStream(outputStream = os);
		if (charsetName.equals("UTF-16")) {
			saxDocumentSerializer.setCharacterEncodingScheme("UTF-16BE");
		}
		return this;
	}

	@Override
	public void endDocument() throws SAXException {
		try {
			super.endDocument();
			outputStream.close();
		} catch (IOException e) {
			throw new SAXException(e);
		} finally {
			outputStream = null;
		}
	}

	private class TypeInfoContentHandler implements ContentHandler {
		private final ValidatorHandler validatorHandler;
		private final boolean preserveWhitespace;
		private TypeInfo typeInfo;

		private TypeInfoContentHandler(ValidatorHandler validatorHandler, boolean preserveWhitespace) {
			this.validatorHandler = validatorHandler;
			this.preserveWhitespace = preserveWhitespace;
		}

		private boolean isXSType(String type) {
			return typeInfo.isDerivedFrom(XMLConstants.W3C_XML_SCHEMA_NS_URI, type, TypeInfo.DERIVATION_RESTRICTION | TypeInfo.DERIVATION_LIST);
		}

		@Override
		public void setDocumentLocator(Locator locator) {
			saxDocumentSerializer.setDocumentLocator(locator);
		}

		@Override
		public void startDocument() throws SAXException {
			saxDocumentSerializer.startDocument();
		}

		@Override
		public void endDocument() throws SAXException {
			saxDocumentSerializer.endDocument();
		}

		@Override
		public void startPrefixMapping(String prefix, String uri) throws SAXException {
			saxDocumentSerializer.startPrefixMapping(prefix, uri);
		}

		@Override
		public void endPrefixMapping(String prefix) throws SAXException {
			saxDocumentSerializer.endPrefixMapping(prefix);
		}

		@Override
		public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
			if (atts.getLength() > 0) {
				EncodingAlgorithmAttributesImpl eatts = new EncodingAlgorithmAttributesImpl();
				for (int i = 0; i < atts.getLength(); ++i) {
					typeInfo = validatorHandler.getTypeInfoProvider().getAttributeTypeInfo(i);
					final String value = atts.getValue(i);
					if (value.length() > 2 && isXSType("decimal")) {
						eatts.addAttribute(atts.getURI(i), atts.getLocalName(i), atts.getQName(i), atts.getType(i), value, false, RestrictedAlphabet.NUMERIC_CHARACTERS);
					} else if (value.length() > 9 && value.indexOf('+', 10) < 0 && isXSType("date")) {
						eatts.addAttribute(atts.getURI(i), atts.getLocalName(i), atts.getQName(i), atts.getType(i), value, false, RestrictedAlphabet.DATE_TIME_CHARACTERS);
					} else if (value.length() > 2 && isXSType("boolean")) {
						Object booleans = BuiltInEncodingAlgorithmFactory.booleanEncodingAlgorithm.convertFromCharacters(value.toCharArray(), 0, value.length());
						eatts.addAttributeWithBuiltInAlgorithmData(atts.getURI(i), atts.getLocalName(i), atts.getQName(i), EncodingAlgorithmIndexes.BOOLEAN, booleans);
					} else {
						eatts.addAttribute(atts.getURI(i), atts.getLocalName(i), atts.getQName(i), atts.getType(i), value);
					}
				}
				atts = eatts;
			}
			typeInfo = validatorHandler.getTypeInfoProvider().getElementTypeInfo();
			saxDocumentSerializer.startElement(uri, localName, qName, atts);
		}

		@Override
		public void endElement(String uri, String localName, String qName) throws SAXException {
			typeInfo = null;
			saxDocumentSerializer.endElement(uri, localName, qName);
		}

		@Override
		public void characters(char[] ch, int start, int length) throws SAXException {
			if (typeInfo != null) {
				if (length > 2 && isXSType("decimal")) {
					saxDocumentSerializer.numericCharacters(ch, start, length);
				} else if (length > 9 && notContains(ch, start + 10, length - 10, '+') && isXSType("date")) {
					saxDocumentSerializer.dateTimeCharacters(ch, start, length);
				} else if (length > 2 && isXSType("boolean")) {
					boolean[] booleans = (boolean[]) BuiltInEncodingAlgorithmFactory.booleanEncodingAlgorithm.convertFromCharacters(ch, start, length);
					saxDocumentSerializer.booleans(booleans, 0, booleans.length);
				} else if (length > 7 && isXSType("base64Binary")) {
					byte[] bytes = (byte[]) BuiltInEncodingAlgorithmFactory.base64EncodingAlgorithm.convertFromCharacters(ch, start, length);
					saxDocumentSerializer.bytes(bytes, 0, bytes.length);
				} else {
					saxDocumentSerializer.characters(ch, start, length);
				}
			} else {
				saxDocumentSerializer.characters(ch, start, length);
			}
		}

		@Override
		public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
			if (preserveWhitespace) {
				saxDocumentSerializer.ignorableWhitespace(ch, start, length);
			}
		}

		@Override
		public void processingInstruction(String target, String data) throws SAXException {
			saxDocumentSerializer.processingInstruction(target, data);
		}

		@Override
		public void skippedEntity(String name) throws SAXException {
			saxDocumentSerializer.skippedEntity(name);
		}
	}

}
