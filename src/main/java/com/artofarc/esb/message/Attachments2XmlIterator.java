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
import java.util.NoSuchElementException;

import javax.mail.MessagingException;
import javax.mail.internet.MimeBodyPart;
import javax.xml.bind.DatatypeConverter;

import com.artofarc.util.IOUtils;

/**
 * Memory optimized representation of an XML as tokens. Only keeps one base64 LOB in memory at a time.
 */
public final class Attachments2XmlIterator implements Iterator<String> {

	private final Iterator<Entry<String, MimeBodyPart>> _iterator;
	private final boolean _remove;
	private int _state;
	private Entry<String, MimeBodyPart> _entry;

	public Attachments2XmlIterator(ESBMessage message, boolean remove) {
		_iterator = message.getAttachments().entrySet().iterator();
		_remove = remove;
	}

	@Override
	public boolean hasNext() {
		return _state < 4;
	}

	@Override
	public String next() {
		switch (_state++) {
		case 0:
			return "<attachments>";
		case 1:
			if (_iterator.hasNext()) {
				_entry = _iterator.next();
				return "<attachment cid=\"cid:" + _entry.getKey() + "\">";
			}
			_state = 4;
			return "</attachments>";
		case 2:
			try (InputStream is = _entry.getValue().getInputStream()) {
				if (_remove) {
					_iterator.remove();
				}
				_entry = null;
				return DatatypeConverter.printBase64Binary(IOUtils.copy(is));
			} catch (IOException | MessagingException e) {
				throw new RuntimeException(e);
			}
		case 3:
			_state = 1;
			return "</attachment>";
		default:
			throw new NoSuchElementException();
		}
	}

	@Override
	public void remove() {
		throw new UnsupportedOperationException();
	}

}
