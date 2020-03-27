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
import com.artofarc.util.XMLFilterBase;

/**
 * Memory optimized representation of the attachments as SAX events. Only keeps one base64 LOB in memory at a time.
 */
public final class Attachments2SAX extends XMLFilterBase {

	private final Iterator<Entry<String, MimeBodyPart>> _iterator;
	private final boolean _remove;

	public Attachments2SAX(ESBMessage message, boolean remove) {
		_iterator = message.getAttachments().entrySet().iterator();
		_remove = remove;
	}

	@Override
	public void parse(InputSource source) throws SAXException {
		AttributesImpl atts = new AttributesImpl();
		getContentHandler().startDocument();
		getContentHandler().startElement(XMLConstants.NULL_NS_URI, "attachments", "attachments", atts);
		while (_iterator.hasNext()) {
			Entry<String, MimeBodyPart> entry = _iterator.next();
			if (atts.getLength() == 0) {
				atts.addAttribute(XMLConstants.NULL_NS_URI, "cid", "cid", "CDATA", "cid:" + entry.getKey());
			} else {
				atts.setAttribute(0, XMLConstants.NULL_NS_URI, "cid", "cid", "CDATA", "cid:" + entry.getKey());
			}
			getContentHandler().startElement(XMLConstants.NULL_NS_URI, "attachment", "attachment", atts);
			char[] ca;
			try (InputStream is = entry.getValue().getInputStream()) {
				if (_remove) {
					_iterator.remove();
				}
				entry = null;
				// Bad API - waste of GC time: Creating a String from a char[] and copying it in another char[]
				ca = DatatypeConverter.printBase64Binary(IOUtils.copy(is)).toCharArray();
			} catch (IOException | MessagingException e) {
				throw new SAXException(e);
			}
			getContentHandler().characters(ca, 0, ca.length);
			getContentHandler().endElement(XMLConstants.NULL_NS_URI, "attachment", "attachment");
		}
		getContentHandler().endElement(XMLConstants.NULL_NS_URI, "attachments", "attachments");
		getContentHandler().endDocument();
	}

}
