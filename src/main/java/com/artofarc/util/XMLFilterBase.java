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
import java.io.InputStream;

import javax.xml.bind.DatatypeConverter;

import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLFilterImpl;

public class XMLFilterBase extends XMLFilterImpl {

	public XMLFilterBase() {
	}

	public XMLFilterBase(XMLReader parent) {
		super(parent);
	}

	@Override
	public void setFeature(String name, boolean value) {
		// Don't throw SAXNotRecognizedException, we assume that the feature "namespace" is standard anyways
	}

	@Override
	public void setProperty(String name, Object value) throws SAXNotRecognizedException, SAXNotSupportedException {
		// Don't throw SAXNotRecognizedException when parent is null, this takes too much performance
		if (getParent() != null) {
			getParent().setProperty(name, value);
		}
	}

	@Override
	public Object getProperty (String name) throws SAXNotRecognizedException, SAXNotSupportedException {
		// Don't throw SAXNotRecognizedException when parent is null, this takes too much performance
		if (getParent() != null) {
			return getParent().getProperty(name);
		}
		return null;
	}

	protected final void reportError(String message) throws SAXException {
		if (getErrorHandler() != null) {
			getErrorHandler().error(new SAXParseException(message, null));
		} else {
			throw new SAXException(message);
		}
	}

	protected final void characters(String str) throws SAXException {
		final int len = str.length();
		if (len > 0) {
			final char[] ch = str.toCharArray();
			for (int i = 0; i < len; ++i) {
				final int c = ch[i];
				if (c < 32 && c != '\t' && c != '\n' && c != '\r') {
					throw new SAXException("Illegal code point for XML: " + c);
				}
			}
			characters(ch, 0, len);
		}
	}

	protected final void base64Characters(InputStream inputStream, int size) throws SAXException, IOException {
		final int chunkSize = IOUtils.MTU / 4 * 3;
		if (size < 0 || size > chunkSize) {
			final byte[] chunk = new byte[chunkSize];
			for (int pos = 0;; pos = 0) {
				do {
					final int len = inputStream.read(chunk, pos, chunkSize - pos);
					if (len < 0) {
						if (pos > 0) {
							final byte[] ba = new byte[pos];
							System.arraycopy(chunk, 0, ba, 0, pos);
							final char[] ch = DatatypeConverter.printBase64Binary(ba).toCharArray();
							characters(ch, 0, ch.length);
						}
						return;
					}
					pos += len;
				} while (pos < chunkSize);
				characters(DatatypeConverter.printBase64Binary(chunk).toCharArray(), 0, IOUtils.MTU);
			}
		} else {
			final char[] ch = DatatypeConverter.printBase64Binary(IOUtils.toByteArray(inputStream)).toCharArray();
			characters(ch, 0, ch.length);
		}
	}

}
