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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import javax.mail.MessagingException;
import javax.xml.XMLConstants;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import com.artofarc.util.CharArrayWriter;
import com.artofarc.util.TypeAwareXMLFilter;
import com.artofarc.util.W3CConstants;

public final class XOPSerializer extends TypeAwareXMLFilter {

	private final ESBMessage _message;
	private final String _defaultContentType;
	private CharArrayWriter _builder;
	private String _contentType;

	public XOPSerializer(ESBMessage message, String contentType) throws SAXException {
		super(message.getSchema());
		_message = message;
		_defaultContentType = contentType;
	}

	@Override
	public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
		_contentType = atts.getValue(W3CConstants.URI_NS_XMLMIME, W3CConstants.NAME_CONTENT_TYPE);
		super.startElement(uri, localName, qName, atts);
	}

	@Override
	public void endElement(String uri, String localName, String qName) throws SAXException {
		if (_builder != null) {
			String cid = UUID.randomUUID().toString();
			ByteBuffer byteBuffer = Base64.getMimeDecoder().decode(StandardCharsets.US_ASCII.encode(_builder.toCharBuffer()));
			try {
				_message.addAttachment(cid, _contentType != null ? _contentType : _defaultContentType, byteBuffer.array(), null);
			} catch (MessagingException e) {
				throw new SAXException(e);
			}
			_builder = null;
			AttributesImpl atts = new AttributesImpl();
			atts.addAttribute(XMLConstants.NULL_NS_URI, W3CConstants.NAME_HREF, W3CConstants.NAME_HREF, "CDATA", "cid:" + cid);
			getReceiverContentHandler().startPrefixMapping(XMLConstants.DEFAULT_NS_PREFIX, W3CConstants.URI_NS_XOP);
			getReceiverContentHandler().startElement(W3CConstants.URI_NS_XOP, W3CConstants.NAME_INCLUDE, W3CConstants.NAME_INCLUDE, atts);
			getReceiverContentHandler().endElement(W3CConstants.URI_NS_XOP, W3CConstants.NAME_INCLUDE, W3CConstants.NAME_INCLUDE);
			getReceiverContentHandler().endPrefixMapping(XMLConstants.DEFAULT_NS_PREFIX);
		}
		super.endElement(uri, localName, qName);
	}

	@Override
	public void characters(char[] ch, int start, int length) throws SAXException {
		if (_builder != null) {
			_builder.write(ch, start, length);
		} else if (typeInfo != null) {
			if (isXSType("base64Binary")) {
				_builder = new CharArrayWriter(length);
				_builder.write(ch, start, length);
			} else {
				super.characters(ch, start, length);
			}
			typeInfo = null;
		} else {
			super.characters(ch, start, length);
		}
	}

}
