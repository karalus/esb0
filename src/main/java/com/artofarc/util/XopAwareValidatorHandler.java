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

import javax.xml.validation.Schema;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

public final class XopAwareValidatorHandler extends TypeAwareXMLFilter {

	private final Set<String> _cids;

	public XopAwareValidatorHandler(Schema schema, Set<String> cids) {
		super(schema);
		_cids = cids;
	}

	@Override
	public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
		if (W3CConstants.isXOPInclude(uri, localName)) {
			if (typeInfo == null || !isXSType("base64Binary")) {
				reportError("Enclosing element not of type xs:base64Binary");
			}
			String href = atts.getValue(W3CConstants.NAME_HREF);
			if (href == null) {
				reportError("Missing required attribute href");
			} else if (!href.startsWith("cid:")) {
				reportError("href must have schema cid, but is " + href);
			} else if (!_cids.contains(href.substring(4))) {
				reportError("Not found in attachments " + href);
			}
			if (getReceiverContentHandler() != null) {
				getReceiverContentHandler().startElement(uri, localName, qName, atts);
			}
		} else {
			super.startElement(uri, localName, qName, atts);
		}
	}

	@Override
	public void endElement(String uri, String localName, String qName) throws SAXException {
		typeInfo = null;
		if (!W3CConstants.isXOPInclude(uri, localName)) {
			super.endElement(uri, localName, qName);
		} else if (getReceiverContentHandler() != null) {
			getReceiverContentHandler().endElement(uri, localName, qName);
		}
	}

}
