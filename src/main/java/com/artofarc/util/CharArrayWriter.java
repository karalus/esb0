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
package com.artofarc.util;

import java.io.IOException;
import java.io.Writer;
import java.nio.CharBuffer;

import org.jvnet.fastinfoset.EncodingAlgorithm;
import org.jvnet.fastinfoset.EncodingAlgorithmException;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

public final class CharArrayWriter extends Writer {

	private char buf[];
	private int pos, count;

	@Deprecated(forRemoval = true)
	public CharArrayWriter(int initialSize) {
	}

	public CharArrayWriter() {
	}

	@Override
	public void write(char[] cbuf, int off, int len) {
		if (buf != null) {
			int newcount = count + len;
			char[] copy = new char[newcount];
			System.arraycopy(buf, pos, copy, 0, count);
			System.arraycopy(cbuf, off, buf = copy, count, len);
			pos = 0;
			count = newcount;
		} else {
			buf = cbuf;
			pos = off;
			count = len;
		}
	}

	@Override
	public void flush() throws IOException {
	}

	@Override
	public void close() throws IOException {
	}

	@Override
	public String toString() {
		return new String(buf, pos, count);
	}

	public int size() {
		return count;
	}

	public CharBuffer toCharBuffer() {
		return CharBuffer.wrap(buf, pos, count);
	}

	public boolean notContains(int ch, int fromIndex) {
		for (int i = fromIndex; i < count; ++i) {
			if (buf[pos + i] == ch) return false;
		}
		return true;
	}

	public void sendTo(ContentHandler contentHandler) throws SAXException {
		contentHandler.characters(buf, pos, count);
	}

	public void sendTo(CharArrayConsumer consumer) throws SAXException {
		consumer.characters(buf, pos, count);
	}

	@SuppressWarnings("unchecked")
	public <T> T encode(EncodingAlgorithm encodingAlgorithm) throws SAXException {
		try {
			return (T) encodingAlgorithm.convertFromCharacters(buf, pos, count);
		} catch (EncodingAlgorithmException e) {
			throw new SAXException(e);
		}
	}

	@FunctionalInterface
	public interface CharArrayConsumer {
		void characters(char ch[], int start, int length) throws SAXException;
	}

}
