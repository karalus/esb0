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

import java.util.ArrayDeque;

import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXSource;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class NamespaceBeautifier extends XMLParserBase {

	private final Source _source;
	private final Transformer _transformer;
	private final ArrayDeque<String[]> _attsPrefixes = new ArrayDeque<>();

	public NamespaceBeautifier(Source source, Transformer transformer) {
		super(true, null);
		_source = source;
		_transformer = transformer;
	}

	@Override
	public void startPrefixMapping(String prefix, String uri) {
	}

	@Override
	public void endPrefixMapping(String prefix) {
	}

	@Override
	public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
		int attsLength = atts.getLength();
		String[] attsPrefixes = new String[attsLength];
		for (int i = 0; i < attsLength; ++i) {
			String attUri = atts.getURI(i);
			if (attUri.length() > 0) {
				String prefix = getPrefixFromQName(atts.getQName(i));
				super.startPrefixMapping(prefix, attUri);
				attsPrefixes[i] = prefix;
			}
		}
		_attsPrefixes.push(attsPrefixes);
		super.startElement(uri, localName, localName, atts);
	}

	@Override
	public void endElement(String uri, String localName, String qName) throws SAXException {
		super.endElement(uri, localName, localName);
		for (String prefix : _attsPrefixes.pop()) {
			if (prefix != null) {
				super.endPrefixMapping(prefix);
			}
		}
	}

	@Override
	public void parse(InputSource source) throws SAXException {
		if (source != null) {
			if (_source instanceof SAXSource) {
				SAXSource saxSource = (SAXSource) _source;
				if (saxSource.getInputSource() == null) {
					saxSource.setInputSource(source);
				} else if (saxSource.getInputSource() != source) {
					throw new SAXException("Ambiguous InputSource");
				}
			} else {
				throw new SAXException("Ambiguous source");
			}
		}
		try {
			_transformer.transform(_source, new SAXResult(this));
		} catch (TransformerException e) {
			throw new SAXException(e);
		}
	}

}
