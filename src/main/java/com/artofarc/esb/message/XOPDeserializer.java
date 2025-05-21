/*
 * Copyright 2023 Andre Karalus
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
package com.artofarc.esb.message;

import java.io.IOException;
import java.io.InputStream;

import javax.mail.MessagingException;
import javax.mail.internet.MimeBodyPart;
import javax.xml.parsers.SAXParser;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.artofarc.util.PrefixHandler;
import com.artofarc.util.W3CConstants;
import com.artofarc.util.XMLFilterBase;

public final class XOPDeserializer extends XMLFilterBase {

	private final ESBMessage _message;
	private final PrefixHandler _prefixHandler = new PrefixHandler();
	private SAXParser _saxParser;

	public XOPDeserializer(ESBMessage message) {
		_message = message;
	}

	@Override
	public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
		if (W3CConstants.isXOPInclude(uri, localName)) {
			String href = atts.getValue(W3CConstants.NAME_HREF);
			if (href == null) {
				reportError("Missing required attribute href");
			} else if (!href.startsWith("cid:")) {
				reportError("href must have schema cid, but is " + href);
			} else {
				String cid = href.substring(4);
				MimeBodyPart attachment = _message.getAttachments().remove(cid);
				if (attachment == null) {
					reportError("Not found in attachments " + href);
				} else {
					try (InputStream is = attachment.getInputStream()) {
						base64Characters(is, attachment.getSize());
					} catch (IOException | MessagingException e) {
						throw new SAXException(e);
					}
				}
			}
		} else {
			super.startElement(uri, localName, qName, atts);
		}
	}

	@Override
	public void endElement(String uri, String localName, String qName) throws SAXException {
		if (!W3CConstants.isXOPInclude(uri, localName)) {
			super.endElement(uri, localName, qName);
		}
	}

	@Override
	public void startPrefixMapping(String prefix, String uri) throws SAXException {
		_prefixHandler.startPrefixMapping(prefix, uri);
		if (!W3CConstants.URI_NS_XOP.equals(uri)) {
			super.startPrefixMapping(prefix, uri);
		}
	}

	@Override
	public void endPrefixMapping(String prefix) throws SAXException {
		if (!W3CConstants.URI_NS_XOP.equals(_prefixHandler.getNamespace(prefix))) {
			super.endPrefixMapping(prefix);
		}
		_prefixHandler.endPrefixMapping(prefix);
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
