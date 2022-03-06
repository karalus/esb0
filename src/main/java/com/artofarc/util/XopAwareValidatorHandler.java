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

import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.validation.Schema;
import javax.xml.validation.ValidatorHandler;

import org.w3c.dom.TypeInfo;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

public final class XopAwareValidatorHandler extends XMLFilterBase {

	public static final String URI_NS_XOP = "http://www.w3.org/2004/08/xop/include";
	public static final String NAME_INCLUDE = "Include";

	private final Set<String> _cids;
	private final XMLFilterBase _receiver;
	private TypeInfo typeInfo;

	public XopAwareValidatorHandler(Schema schema, Set<String> cids) {
		ValidatorHandler validatorHandler = schema.newValidatorHandler();
		super.setContentHandler(validatorHandler);
		_receiver = new XMLFilterBase() {

			@Override
			public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
				typeInfo = validatorHandler.getTypeInfoProvider().getElementTypeInfo();
				super.startElement(uri, localName, qName, atts);
			}

		};
		validatorHandler.setContentHandler(_receiver);
		_cids = cids;
	}

	public void setContentHandler(ContentHandler handler) {
		_receiver.setContentHandler(handler);
	}

	@Override
	public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
		if (URI_NS_XOP.equals(uri) && NAME_INCLUDE.equals(localName)) {
			if (!typeInfo.isDerivedFrom(XMLConstants.W3C_XML_SCHEMA_NS_URI, "base64Binary", TypeInfo.DERIVATION_RESTRICTION | TypeInfo.DERIVATION_EXTENSION)) {
				throw new SAXException("Enclosing element not of type xs:base64Binary");
			}
			String href = atts.getValue("href");
			if (href == null) {
				throw new SAXException("Missing required attribute href");
			}
			if (!href.startsWith("cid:") || !_cids.contains(href.substring(4))) {
				throw new SAXException("Not found in attachments " + href);
			}
		} else {
			super.startElement(uri, localName, qName, atts);
		}
	}

	@Override
	public void endElement(String uri, String localName, String qName) throws SAXException {
		if (!(URI_NS_XOP.equals(uri) && NAME_INCLUDE.equals(localName))) {
			super.endElement(uri, localName, qName);
		}
	}

}
