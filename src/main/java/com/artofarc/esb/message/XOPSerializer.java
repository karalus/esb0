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
import static javax.xml.XMLConstants.*;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import com.artofarc.util.CharArrayWriter;
import com.artofarc.util.TypeAwareXMLFilter;
import static com.artofarc.util.W3CConstants.*;

public final class XOPSerializer extends TypeAwareXMLFilter {

	private final ESBMessage _message;
	private final int _threshold;
	private final String _defaultContentType;
	private CharArrayWriter _builder;
	private String _contentType;

	public XOPSerializer(ESBMessage message, int threshold, String contentType) throws SAXException {
		super(message.getSchema());
		_message = message;
		_threshold = threshold;
		_defaultContentType = contentType;
	}

	@Override
	public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
		_contentType = atts.getValue(URI_NS_XMLMIME, NAME_CONTENT_TYPE);
		super.startElement(uri, localName, qName, atts);
	}

	@Override
	public void endElement(String uri, String localName, String qName) throws SAXException {
		if (_builder != null) {
			if (_builder.size() < _threshold) {
				_builder.sendTo(getContentHandler());
			} else {
				String cid = UUID.randomUUID().toString();
				ByteBuffer byteBuffer = Base64.getMimeDecoder().decode(StandardCharsets.US_ASCII.encode(_builder.toCharBuffer()));
				try {
					_message.addAttachment(cid, _contentType != null ? _contentType : _defaultContentType, byteBuffer.array(), null);
				} catch (MessagingException e) {
					throw new SAXException(e);
				}
				AttributesImpl atts = new AttributesImpl();
				atts.addAttribute(NULL_NS_URI, NAME_HREF, NAME_HREF, "CDATA", "cid:" + cid);
				getReceiverContentHandler().startPrefixMapping(DEFAULT_NS_PREFIX, URI_NS_XOP);
				getReceiverContentHandler().startElement(URI_NS_XOP, NAME_INCLUDE, NAME_INCLUDE, atts);
				getReceiverContentHandler().endElement(URI_NS_XOP, NAME_INCLUDE, NAME_INCLUDE);
				getReceiverContentHandler().endPrefixMapping(DEFAULT_NS_PREFIX);
			}
			_builder = null;
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
