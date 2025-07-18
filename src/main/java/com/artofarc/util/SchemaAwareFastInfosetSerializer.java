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
import org.jvnet.fastinfoset.ExternalVocabulary;
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

	private final SAXDocumentSerializer saxDocumentSerializer = new SAXDocumentSerializer();
	private ExternalVocabulary externalVocabulary;
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

	public void setExternalVocabulary(ExternalVocabulary v) {
		if (externalVocabulary != v) {
			if (v != null) {
				saxDocumentSerializer.setExternalVocabulary(v);
			} else {
				saxDocumentSerializer.setVocabulary(null);
			}
			externalVocabulary = v;
		}
	}

	public ContentHandler getContentHandler(OutputStream os, String charsetName) {
		saxDocumentSerializer.reset();
		saxDocumentSerializer.setOutputStream(outputStream = os);
		if (charsetName.equals("UTF-16")) {
			saxDocumentSerializer.setCharacterEncodingScheme(FastInfosetSerializer.UTF_16BE);
		} else {
			saxDocumentSerializer.setCharacterEncodingScheme(charsetName);
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
		private CharArrayWriter _builder;

		private TypeInfoContentHandler(ValidatorHandler validatorHandler, boolean preserveWhitespace) {
			this.validatorHandler = validatorHandler;
			this.preserveWhitespace = preserveWhitespace;
		}

		private boolean isXSType(String type) {
			return typeInfo.isDerivedFrom(XMLConstants.W3C_XML_SCHEMA_NS_URI, type, TypeInfo.DERIVATION_RESTRICTION | TypeInfo.DERIVATION_EXTENSION);
		}

		private boolean isXSTypeOrList(String type) {
			return typeInfo.isDerivedFrom(XMLConstants.W3C_XML_SCHEMA_NS_URI, type, TypeInfo.DERIVATION_RESTRICTION | TypeInfo.DERIVATION_EXTENSION | TypeInfo.DERIVATION_LIST);
		}

		private void flushBuilder() throws SAXException {
			if (_builder != null) {
				_builder.sendTo(saxDocumentSerializer);
				_builder = null;
			}
		}

		@Override
		public void setDocumentLocator(Locator locator) {
			saxDocumentSerializer.setDocumentLocator(locator);
		}

		@Override
		public void startDocument() throws SAXException {
			_builder = null;
			saxDocumentSerializer.startDocument();
		}

		@Override
		public void endDocument() throws SAXException {
			saxDocumentSerializer.endDocument();
		}

		@Override
		public void startPrefixMapping(String prefix, String uri) throws SAXException {
			flushBuilder();
			saxDocumentSerializer.startPrefixMapping(prefix, uri);
		}

		@Override
		public void endPrefixMapping(String prefix) throws SAXException {
			saxDocumentSerializer.endPrefixMapping(prefix);
		}

		@Override
		public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
			flushBuilder();
			if (atts.getLength() > 0) {
				EncodingAlgorithmAttributesImpl eatts = new EncodingAlgorithmAttributesImpl();
				for (int i = 0; i < atts.getLength(); ++i) {
					typeInfo = validatorHandler.getTypeInfoProvider().getAttributeTypeInfo(i);
					final String value = atts.getValue(i);
					if (typeInfo != null) {
						if (value.length() > 2 && isXSType("decimal")) {
							eatts.addAttribute(atts.getURI(i), atts.getLocalName(i), atts.getQName(i), atts.getType(i), value, false, RestrictedAlphabet.NUMERIC_CHARACTERS);
						} else if (value.length() > 9 && value.indexOf('+', 10) < 0 && isXSType("date")) {
							eatts.addAttribute(atts.getURI(i), atts.getLocalName(i), atts.getQName(i), atts.getType(i), value, false, RestrictedAlphabet.DATE_TIME_CHARACTERS);
						} else if (value.length() > 18 && value.indexOf('.', 19) < 0 && value.indexOf('+', 19) < 0 && isXSType("dateTime")) {
							eatts.addAttribute(atts.getURI(i), atts.getLocalName(i), atts.getQName(i), atts.getType(i), value, false, RestrictedAlphabet.DATE_TIME_CHARACTERS);
						} else if (value.length() > 2 && isXSTypeOrList("boolean")) {
							Object booleans = BuiltInEncodingAlgorithmFactory.booleanEncodingAlgorithm.convertFromCharacters(value.toCharArray(), 0, value.length());
							eatts.addAttributeWithBuiltInAlgorithmData(atts.getURI(i), atts.getLocalName(i), atts.getQName(i), EncodingAlgorithmIndexes.BOOLEAN, booleans);
						} else {
							eatts.addAttribute(atts.getURI(i), atts.getLocalName(i), atts.getQName(i), atts.getType(i), value);
						}
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
			if (_builder != null) {
				if (_builder.size() > 2 && isXSType("decimal")) {
					_builder.sendTo(saxDocumentSerializer::numericCharacters);
				} else if (_builder.size() > 9 && _builder.notContains('+', 10) && isXSType("date")) {
					_builder.sendTo(saxDocumentSerializer::dateTimeCharacters);
				} else if (_builder.size() > 18 && _builder.notContains('.', 19) && _builder.notContains('+', 19) && isXSType("dateTime")) {
					_builder.sendTo(saxDocumentSerializer::dateTimeCharacters);
				} else if (_builder.size() > 2 && isXSTypeOrList("boolean")) {
					boolean[] booleans = _builder.encode(BuiltInEncodingAlgorithmFactory.booleanEncodingAlgorithm);
					saxDocumentSerializer.booleans(booleans, 0, booleans.length);
				} else if (_builder.size() > 7 && isXSType("base64Binary")) {
					byte[] bytes = _builder.encode(BuiltInEncodingAlgorithmFactory.base64EncodingAlgorithm);
					saxDocumentSerializer.bytes(bytes, 0, bytes.length);
				} else {
					_builder.sendTo(saxDocumentSerializer);
				}
				_builder = null;
			}
			typeInfo = null;
			saxDocumentSerializer.endElement(uri, localName, qName);
		}

		@Override
		public void characters(char[] ch, int start, int length) throws SAXException {
			if (typeInfo != null) {
				if (_builder == null) {
					_builder = new CharArrayWriter();
				}
				_builder.write(ch, start, length);
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
