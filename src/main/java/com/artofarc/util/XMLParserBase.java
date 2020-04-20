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
package com.artofarc.util;

import java.util.ArrayDeque;
import java.util.Map;

import javax.xml.XMLConstants;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

public abstract class XMLParserBase extends XMLFilterBase {

	private final boolean _createDocumentEvents;
	private final Map<String, String> _prefixMap, _nsMap;
	private final ArrayDeque<String> _stack = new ArrayDeque<>();

	public XMLParserBase(boolean createDocumentEvents, Map<String, String> prefixMap, Map<String, String> nsMap) {
		_createDocumentEvents = createDocumentEvents;
		_prefixMap = prefixMap;
		_nsMap = nsMap;
		String defaultNsUri = _prefixMap != null ? _prefixMap.get(XMLConstants.DEFAULT_NS_PREFIX) : null;
		_stack.push(defaultNsUri != null ? defaultNsUri : XMLConstants.NULL_NS_URI);
	}

	public final String getDefaultUri() {
		return _stack.peek();
	}

	public final String createQName(String uri, String localName) {
		if (_nsMap != null) {
			String prefix = _nsMap.get(uri);
			if (prefix != null && prefix.length() > 0) {
				return prefix + ':' + localName;
			}
		}
		return localName;
	}

	@Override
	public void startDocument() throws SAXException {
		if (_createDocumentEvents) {
			super.startDocument();
		}
		if (_prefixMap != null) {
			for (Map.Entry<String, String> entry : _prefixMap.entrySet()) {
				startPrefixMapping(entry.getKey(), entry.getValue());
			}
		}
	}

	@Override
	public void endDocument() throws SAXException {
		if (_prefixMap != null) {
			for (Map.Entry<String, String> entry : _prefixMap.entrySet()) {
				endPrefixMapping(entry.getKey());
			}
		}
		if (_createDocumentEvents) {
			super.endDocument();
		}
	}

	@Override
	public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
		String defaultNsUri = _stack.peek();
		if (localName == qName && !uri.equals(defaultNsUri)) {
			startPrefixMapping(XMLConstants.DEFAULT_NS_PREFIX, defaultNsUri = uri);
		}
		super.startElement(uri, localName, qName, atts);
		_stack.push(defaultNsUri);
	}

	@Override
	public void endElement(String uri, String localName, String qName) throws SAXException {
		super.endElement(uri, localName, qName);
		if (!_stack.pop().equals(_stack.peek())) {
			endPrefixMapping(XMLConstants.DEFAULT_NS_PREFIX);
		}
	}

	public final void characters(String value) throws SAXException {
		if (value.length() > 0) {
			characters(value.toCharArray(), 0, value.length());
		}
	}

}
