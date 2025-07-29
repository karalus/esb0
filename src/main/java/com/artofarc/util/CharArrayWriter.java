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

import java.nio.CharBuffer;

import org.jvnet.fastinfoset.EncodingAlgorithm;
import org.jvnet.fastinfoset.EncodingAlgorithmException;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

public final class CharArrayWriter extends java.io.CharArrayWriter {

	/**
	 * For memory optimization allocate as least as many bytes as the first len in call to {@link #write(char[], int, int)}.
	 */
	public CharArrayWriter(int initialSize) {
		super(initialSize);
	}

	public CharBuffer toCharBuffer() {
		return CharBuffer.wrap(buf, 0, count);
	}

	public boolean notContains(int ch, int fromIndex) {
		for (int i = fromIndex; i < count; ++i) {
			if (buf[i] == ch) return false;
		}
		return true;
	}

	public void sendTo(ContentHandler contentHandler) throws SAXException {
		contentHandler.characters(buf, 0, count);
	}

	public void sendTo(CharArrayConsumer consumer) throws SAXException {
		consumer.characters(buf, 0, count);
	}

	@SuppressWarnings("unchecked")
	public <T> T encode(EncodingAlgorithm encodingAlgorithm) throws SAXException {
		try {
			return (T) encodingAlgorithm.convertFromCharacters(buf, 0, count);
		} catch (EncodingAlgorithmException e) {
			throw new SAXException(e);
		}
	}

	@FunctionalInterface
	public interface CharArrayConsumer {
		void characters(char ch[], int start, int length) throws SAXException;
	}

}
