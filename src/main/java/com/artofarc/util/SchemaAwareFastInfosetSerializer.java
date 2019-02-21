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
package com.artofarc.util;

import javax.xml.XMLConstants;
import javax.xml.validation.Schema;
import javax.xml.validation.ValidatorHandler;

import org.jvnet.fastinfoset.EncodingAlgorithmIndexes;
import org.jvnet.fastinfoset.FastInfosetSerializer;
import org.jvnet.fastinfoset.sax.helpers.EncodingAlgorithmAttributesImpl;
import org.w3c.dom.TypeInfo;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

import com.sun.xml.fastinfoset.algorithm.BuiltInEncodingAlgorithmFactory;
import com.sun.xml.fastinfoset.sax.SAXDocumentSerializer;

// Not thread safe
public final class SchemaAwareFastInfosetSerializer implements AutoCloseable {

	private final SAXDocumentSerializer saxDocumentSerializer = new SAXDocumentSerializer();
	private final ValidatorHandler validatorHandler;

	public SchemaAwareFastInfosetSerializer(Schema schema) {
		if (schema != null) {
			validatorHandler = schema.newValidatorHandler();
			validatorHandler.setContentHandler(new TypeInfoContentHandler());
		} else {
			validatorHandler = null;
		}
	}

	@Override
	public void close() {
	}

	public FastInfosetSerializer getFastInfosetSerializer() {
		return saxDocumentSerializer;
	}

	public ContentHandler getContentHandler() {
		return validatorHandler != null ? validatorHandler : saxDocumentSerializer;
	}

	private class TypeInfoContentHandler implements ContentHandler {
		private TypeInfo typeInfo;

		private boolean isXSType(String type) {
			return typeInfo.isDerivedFrom(XMLConstants.W3C_XML_SCHEMA_NS_URI, type, TypeInfo.DERIVATION_RESTRICTION);
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
				EncodingAlgorithmAttributesImpl eatts = new EncodingAlgorithmAttributesImpl(atts);
				for (int i = 0; i < atts.getLength(); ++i) {
					typeInfo = validatorHandler.getTypeInfoProvider().getAttributeTypeInfo(i);
					char[] ch = atts.getValue(i).toCharArray();
					if (isXSType("base64Binary")) {
						byte[] bytes = (byte[]) BuiltInEncodingAlgorithmFactory.base64EncodingAlgorithm.convertFromCharacters(ch, 0, ch.length);
						eatts.replaceWithAttributeAlgorithmData(i, null, EncodingAlgorithmIndexes.BASE64, bytes);
					} else if (isXSType("int")) {
						int[] ints = (int[]) BuiltInEncodingAlgorithmFactory.intEncodingAlgorithm.convertFromCharacters(ch, 0, ch.length);
						eatts.replaceWithAttributeAlgorithmData(i, null, EncodingAlgorithmIndexes.INT, ints);
					} else if (isXSType("long")) {
						long[] longs = (long[]) BuiltInEncodingAlgorithmFactory.longEncodingAlgorithm.convertFromCharacters(ch, 0, ch.length);
						eatts.replaceWithAttributeAlgorithmData(i, null, EncodingAlgorithmIndexes.LONG, longs);
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
				if (isXSType("base64Binary")) {
					byte[] bytes = (byte[]) BuiltInEncodingAlgorithmFactory.base64EncodingAlgorithm.convertFromCharacters(ch, start, length);
					saxDocumentSerializer.bytes(bytes, 0, bytes.length);
				} else if (isXSType("int")) {
					int[] ints = (int[]) BuiltInEncodingAlgorithmFactory.intEncodingAlgorithm.convertFromCharacters(ch, start, length);
					saxDocumentSerializer.ints(ints, 0, ints.length);
				} else if (isXSType("long")) {
					long[] longs = (long[]) BuiltInEncodingAlgorithmFactory.longEncodingAlgorithm.convertFromCharacters(ch, start, length);
					saxDocumentSerializer.longs(longs, 0, longs.length);
				} else {
					saxDocumentSerializer.characters(ch, start, length);
				}
			} else {
				saxDocumentSerializer.characters(ch, start, length);
			}
		}

		@Override
		public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
			saxDocumentSerializer.ignorableWhitespace(ch, start, length);
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
