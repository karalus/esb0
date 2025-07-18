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

/**
 * Memory optimized alternative to {@link java.io.CharArrayWriter}(no copying of arrays).<br>Does not work if cbuf is reused like in {@link java.io.Reader#transferTo(Writer)}!
 */
public final class CharArrayWriter extends Writer {

	private char buf[];
	private int pos, count, hashCode;

	@Deprecated(forRemoval = true)
	public CharArrayWriter(int initialSize) {
	}

	public CharArrayWriter() {
	}

	@Override
	public void write(char[] cbuf, int off, int len) {
		if (buf != null) {
			if (buf == cbuf && hashCode(cbuf, pos, count) != hashCode) {
				throw new IllegalStateException("cbuf must not be reused");
			}
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
			hashCode = hashCode(cbuf, off, len);
		}
	}

	private static int hashCode(char[] cbuf, int off, int len) {
		int result = 1;
		for (int i = 0; i < len; ++i) {
			result = 31 * result + cbuf[off + i];
		}
		return result;
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

	public int trim() {
		int i = 0, skip = 0;
		while (i < count && isWhiteSpace(buf[pos + i++])) ++skip;
		pos += skip;
		count -= skip;
		i = count;
		while (i > 0 && isWhiteSpace(buf[pos + --i])) --count;
		return count;
	}

	private static boolean isWhiteSpace(int c) {
		return c <= 32 && (c == ' ' || c == '\t' || c == '\n' || c == '\r');
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
