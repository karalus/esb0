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
package com.artofarc.esb.message;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map.Entry;

import javax.mail.MessagingException;
import javax.mail.internet.MimeBodyPart;
import javax.xml.XMLConstants;
import javax.xml.bind.DatatypeConverter;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import com.artofarc.util.IOUtils;
import com.artofarc.util.URLUtils;
import com.artofarc.util.XMLFilterBase;

/**
 * Memory optimized representation of the attachments as SAX events. Only keeps one base64 LOB in memory at a time.
 */
public final class Attachments2SAX extends XMLFilterBase {

	private final Iterator<Entry<String, MimeBodyPart>> _iterator;
	private final boolean _consume;

	public Attachments2SAX(ESBMessage message, boolean consume) {
		_iterator = message.getAttachments().entrySet().iterator();
		_consume = consume;
	}

	@Override
	public void parse(InputSource source) throws SAXException, IOException {
		AttributesImpl atts = new AttributesImpl();
		startDocument();
		startElement(XMLConstants.NULL_NS_URI, "attachments", "attachments", atts);
		while (_iterator.hasNext()) {
			Entry<String, MimeBodyPart> entry = _iterator.next();
			String cid = "cid:" + URLUtils.encode(entry.getKey());
			String contentType;
			try {
				contentType = entry.getValue().getContentType();
			} catch (MessagingException e) {
				throw new SAXException(e);
			}
			if (atts.getLength() == 0) {
				atts.addAttribute(XMLConstants.NULL_NS_URI, "cid", "cid", "CDATA", cid);
				atts.addAttribute(XMLConstants.NULL_NS_URI, "contentType", "contentType", "CDATA", contentType);
			} else {
				atts.setAttribute(0, XMLConstants.NULL_NS_URI, "cid", "cid", "CDATA", cid);
				atts.setAttribute(1, XMLConstants.NULL_NS_URI, "contentType", "contentType", "CDATA", contentType);
			}
			startElement(XMLConstants.NULL_NS_URI, "attachment", "attachment", atts);
			if (_consume) {
				try (InputStream is = entry.getValue().getInputStream()) {
					_iterator.remove();
					entry = null;
					characters(DatatypeConverter.printBase64Binary(IOUtils.copy(is)));
				} catch (MessagingException e) {
					throw new SAXException(e);
				}
			}
			endElement(XMLConstants.NULL_NS_URI, "attachment", "attachment");
		}
		endElement(XMLConstants.NULL_NS_URI, "attachments", "attachments");
		endDocument();
	}

}
