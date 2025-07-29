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

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import javax.mail.MessagingException;
import static javax.xml.XMLConstants.*;

import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import com.artofarc.util.ByteArrayOutputStream;
import com.artofarc.util.TypeAwareXMLFilter;
import static com.artofarc.util.W3CConstants.*;

public final class XOPSerializer extends TypeAwareXMLFilter {

	private final ESBMessage _message;
	private final int _threshold;
	private final String _defaultContentType;
	private ByteArrayOutputStream _builder;

	public XOPSerializer(ESBMessage message, int threshold, String contentType) throws SAXException {
		super(message.getSchema());
		_message = message;
		_threshold = threshold;
		_defaultContentType = contentType;
	}

	private void flushBuilder() throws SAXException {
		if (_builder != null) {
			CharBuffer charBuffer = StandardCharsets.US_ASCII.decode(_builder.toByteBuffer());
			getContentHandler().characters(charBuffer.array(), charBuffer.position(), charBuffer.remaining());
			_builder = null;
		}
	}

	@Override
	public void startXOPInclude(String href) throws SAXException {
		// already serialized
		flushBuilder();
	}

	@Override
	public void endElement(String uri, String localName, String qName) throws SAXException {
		if (_builder != null) {
			if (_builder.size() < _threshold) {
				flushBuilder();
			} else {
				String cid = UUID.randomUUID().toString();
				try {
					_message.addAttachment(cid, _contentType != null ? _contentType : _defaultContentType, Base64.getMimeDecoder().wrap(_builder.getByteArrayInputStream()), null);
				} catch (MessagingException e) {
					throw new SAXException(e);
				}
				getReceiverContentHandler().startPrefixMapping(DEFAULT_NS_PREFIX, URI_NS_XOP);
				AttributesImpl atts = new AttributesImpl();
				atts.addAttribute(NULL_NS_URI, NAME_HREF, NAME_HREF, "CDATA", "cid:" + cid);
				getReceiverContentHandler().startElement(URI_NS_XOP, NAME_INCLUDE, NAME_INCLUDE, atts);
				getReceiverContentHandler().endElement(URI_NS_XOP, NAME_INCLUDE, NAME_INCLUDE);
				getReceiverContentHandler().endPrefixMapping(DEFAULT_NS_PREFIX);
				_builder = null;
			}
		}
		super.endElement(uri, localName, qName);
	}

	@Override
	public void characters(char[] ch, int start, int length) throws SAXException {
		if (_builder == null && isBase64Binary()) {
			_builder = new ByteArrayOutputStream();
		}
		if (_builder != null) {
			for (int i = 0; i < length; ++i) {
				_builder.write(ch[start + i]);
			}
		} else {
			super.characters(ch, start, length);
		}
	}

}
