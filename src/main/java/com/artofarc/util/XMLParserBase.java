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
	private final NamespaceMap _namespaceMap;
	private final ArrayDeque<String> _stack = new ArrayDeque<>();

	public XMLParserBase(boolean createDocumentEvents, NamespaceMap namespaceMap) {
		_createDocumentEvents = createDocumentEvents;
		_namespaceMap = namespaceMap;
		String defaultNsUri = namespaceMap != null ? namespaceMap.getNamespaceURI(XMLConstants.DEFAULT_NS_PREFIX) : null;
		_stack.push(defaultNsUri != null ? defaultNsUri : XMLConstants.NULL_NS_URI);
	}

	public final String getDefaultUri() {
		return _stack.peek();
	}

	protected final String getPrefix(String namespaceURI) {
		return _namespaceMap != null ? _namespaceMap.getPrefix(namespaceURI) : null;
	}

	public final String createQName(String uri, String localName) {
		if (_namespaceMap != null) {
			String prefix = _namespaceMap.getPrefix(uri);
			if (prefix != null && prefix.length() > 0) {
				return prefix + ':' + localName;
			}
		}
		return localName;
	}

	public static String getPrefixFromQName(String qName) {
		int i = qName.indexOf(':');
		return i > 0 ? qName.substring(0, i) : XMLConstants.DEFAULT_NS_PREFIX;
	}

	@Override
	public void startDocument() throws SAXException {
		if (_createDocumentEvents) {
			super.startDocument();
		}
		if (_namespaceMap != null) {
			for (Map.Entry<String, String> entry : _namespaceMap.getPrefixes()) {
				super.startPrefixMapping(entry.getKey(), entry.getValue());
			}
		}
	}

	@Override
	public void endDocument() throws SAXException {
		if (_namespaceMap != null) {
			for (Map.Entry<String, String> entry : _namespaceMap.getPrefixes()) {
				super.endPrefixMapping(entry.getKey());
			}
		}
		if (_createDocumentEvents) {
			super.endDocument();
		}
	}

	@Override
	public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
		String defaultNsUri = _stack.peek();
		if (localName.equals(qName) && !uri.equals(defaultNsUri)) {
			super.startPrefixMapping(XMLConstants.DEFAULT_NS_PREFIX, defaultNsUri = uri);
		}
		super.startElement(uri, localName, qName, atts);
		_stack.push(defaultNsUri);
	}

	@Override
	public void endElement(String uri, String localName, String qName) throws SAXException {
		super.endElement(uri, localName, qName);
		if (!_stack.pop().equals(_stack.peek())) {
			super.endPrefixMapping(XMLConstants.DEFAULT_NS_PREFIX);
		}
	}

}
