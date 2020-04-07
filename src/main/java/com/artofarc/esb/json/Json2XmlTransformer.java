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
package com.artofarc.esb.json;

import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.json.Json;
import javax.json.stream.JsonParser;
import javax.json.stream.JsonParserFactory;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;

import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.AttributesImpl;

import com.artofarc.util.XMLFilterBase;
import com.artofarc.util.XSOMHelper;
import com.sun.xml.xsom.XSAttributeUse;
import com.sun.xml.xsom.XSComplexType;
import com.sun.xml.xsom.XSElementDecl;
import com.sun.xml.xsom.XSSchemaSet;
import com.sun.xml.xsom.XSTerm;
import com.sun.xml.xsom.XSWildcard;

public final class Json2XmlTransformer {

	private static final boolean VALIDATE_PREFIXES = false;
	private static final JsonParserFactory jsonParserFactory = Json.createParserFactory(null);

	private final boolean _createDocumentEvents;
	private final boolean _includeRoot;
	private final QName _rootElement;
	private final XSComplexType _complexType;
	private final Map<String, String> _prefixMap, _nsMap;
	private final String attributePrefix = "@";
	private final String valueWrapper = "value";

	static <V> Map<V, String> inverseMap(Collection<Map.Entry<String, V>> entrySet) {
		Map<V, String> result = new HashMap<>();
		for (Map.Entry<String, V> entry : entrySet) {
			String old = result.get(entry.getValue());
			if (old == null || old.length() > entry.getKey().length()) {
				result.put(entry.getValue(), entry.getKey());
			}
		}
		return result;
	}

	public Json2XmlTransformer(XSSchemaSet schemaSet, boolean createDocumentEvents, String rootElement, boolean includeRoot, Map<String, String> prefixMap) {
		if (schemaSet == null) {
			schemaSet = XSOMHelper.anySchema;
			rootElement = "root";
		}
		_rootElement = QName.valueOf(rootElement);
		_includeRoot = includeRoot;
		XSElementDecl element = schemaSet.getElementDecl(_rootElement.getNamespaceURI(), _rootElement.getLocalPart());
		_complexType = element.getType().asComplexType();
		_prefixMap = prefixMap;
		_nsMap = prefixMap != null ? inverseMap(prefixMap.entrySet()) : null;
		_createDocumentEvents = createDocumentEvents;
	}

	public XMLReader createParser() {
		return new Parser();
	}

	private static final class Source implements Locator {

		private final JsonParser jsonParser;
		private final InputSource _source;

		private Source(InputSource source) throws SAXException {
			if (source.getByteStream() != null) {
				if (source.getEncoding() != null) {
					jsonParser = jsonParserFactory.createParser(source.getByteStream(), Charset.forName(source.getEncoding()));
				} else {
					jsonParser = jsonParserFactory.createParser(source.getByteStream());
				}
			} else if (source.getCharacterStream() != null) {
				jsonParser = jsonParserFactory.createParser(source.getCharacterStream());
			} else {
				throw new SAXException("InputSource must provide a stream");
			}
			_source = source;
		}

		@Override
		public String getPublicId() {
			return _source.getPublicId();
		}

		@Override
		public String getSystemId() {
			return _source.getSystemId();
		}

		@Override
		public int getLineNumber() {
			return (int) jsonParser.getLocation().getLineNumber();
		}

		@Override
		public int getColumnNumber() {
			return (int) jsonParser.getLocation().getColumnNumber();
		}
	}

	private static final class Element {
		Element(String uri, String localName, String qName) {
			this.uri = uri;
			this.localName = localName;
			this.qName = qName;
		}

		final String uri, localName, qName;
	}

	private String createQName(String uri, String localName) {
		if (_nsMap != null) {
			String prefix = _nsMap.get(uri);
			if (prefix != null && prefix.length() > 0) {
				return prefix + ':' + localName;
			}
		}
		return localName;
	}

	// Not thread safe
	private final class Parser extends XMLFilterBase {
		
		final XSOMHelper _xsomHelper = new XSOMHelper(_complexType);
		final ArrayDeque<Element> _stack = new ArrayDeque<>();
		final AttributesImpl _atts = new AttributesImpl();

		String keyName, uri;
		boolean attribute, simpleContent;
		int any = -1;

		private void writeValue(String value, String type) throws SAXException {
			if (attribute) {
				XSAttributeUse attributeUse = _xsomHelper.getAttributeUse(uri, keyName);
				if (attributeUse != null) {
					uri = attributeUse.getDecl().getTargetNamespace();
				} else {
					XSWildcard wildcard = _xsomHelper.getAttributeWildcard();
					if (wildcard != null) {
						if (uri == null) {
							uri = XMLConstants.NULL_NS_URI;
						}
						if (!wildcard.acceptsNamespace(uri)) {
							throw new SAXException("Wildcard attribute does not accept namespace: " + uri);
						}
					} else {
						throw new SAXException("Attribute not valid: " + new QName(uri, keyName));
					}
				}
				_atts.addAttribute(uri, keyName, createQName(uri, keyName), "CDATA", value);
			} else {
				final ContentHandler ch = getContentHandler();
				if ((simpleContent || any >= 0) && valueWrapper.equals(keyName)) {
					simpleContent = true;
					ch.characters(value.toCharArray(), 0, value.length());
				} else if (_xsomHelper.getCurrentComplexType().isMixed() && valueWrapper.equals(keyName)) {
					ch.characters(value.toCharArray(), 0, value.length());
				} else {
					uri = _xsomHelper.matchElement(uri, keyName).apply(XSOMHelper.GetNamespace);
					if (_xsomHelper.isLastElementAny()) {
						if (any < 0) {
							any = _stack.size();
						}
					} else {
						if (_xsomHelper.getSimpleType() == null) {
							throw new SAXException("Expected simple type: " + new QName(uri, keyName));
						}
					}
					final String qName = createQName(uri, keyName);
					if (value != null) {
						if (any >= 0 && type != null) {
							if (any == _stack.size()) {
								ch.startPrefixMapping("xsi", XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI);
							}
							_atts.addAttribute(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type", "xsi:type", "CDATA", type);
						}
						ch.startElement(uri, keyName, qName, _atts);
						ch.characters(value.toCharArray(), 0, value.length());
						ch.endElement(uri, keyName, qName);
						if (any >= 0 && type != null) {
							_atts.clear();
							if(any == _stack.size()) {
								ch.endPrefixMapping("xsi");
							}
						}
					} else if (any >= 0 || _xsomHelper.matchElement(uri, keyName).asElementDecl().isNillable()) {
						ch.startPrefixMapping("xsi", XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI);
						_atts.addAttribute(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "nil", "xsi:nil", "CDATA", "true");
						ch.startElement(uri, keyName, qName, _atts);
						ch.endElement(uri, keyName, qName);
						_atts.clear();
						ch.endPrefixMapping("xsi");
					}
					if (!_xsomHelper.isInArray()) {
						keyName = null;
						if (_stack.size() == any) {
							any = -1;
							_xsomHelper.endAny();
						}
					}
				}
			}
			uri = null;
		}

		@Override
		public void parse(InputSource inputSource) throws SAXException {
			final ContentHandler ch = getContentHandler();
			if (ch == null) {
				throw new SAXException("ContentHandler not set");
			}
			Source source = new Source(inputSource);
			setDocumentLocator(source);
			if (_createDocumentEvents) ch.startDocument();
			if (_prefixMap != null) {
				for (Map.Entry<String, String> entry : _prefixMap.entrySet()) {
					ch.startPrefixMapping(entry.getKey(), entry.getValue());
				}
			}
			try (final JsonParser jsonParser = source.jsonParser) {
				Element e = null;
				while (jsonParser.hasNext()) {
					switch (jsonParser.next()) {
					case START_OBJECT:
						if (keyName == null && !_xsomHelper.isInArray()) {
							// assume where on root
							if (_includeRoot) {
								continue;
							} else {
								keyName = _rootElement.getLocalPart();
								uri = _rootElement.getNamespaceURI();
							}
						} else {
							if (_includeRoot && _stack.isEmpty()) {
								if (!keyName.equals(_rootElement.getLocalPart())) {
									throw new SAXException("JSON must start with: " + _rootElement.getLocalPart());
								}
								uri = _rootElement.getNamespaceURI();
							} else {
								XSTerm term = _xsomHelper.matchElement(uri, keyName);
								uri = term.apply(XSOMHelper.GetNamespace);
								if (keyName == null) {
									keyName = term.apply(XSOMHelper.GetName);
								}
								if (_xsomHelper.isLastElementAny()) {
									if (any < 0) {
										any = _stack.size();
										ch.startPrefixMapping("xsi", XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI);
									}
								} else {
									if (!_xsomHelper.isLastElementComplex()) {
										throw new SAXException("Expected complex type: " + new QName(uri, keyName));
									}
									simpleContent = _xsomHelper.getSimpleType() != null;
								}
							}
						}
						_stack.push(e = new Element(uri, keyName, createQName(uri, keyName)));
						uri = null;
						break;
					case END_OBJECT:
						e = _stack.poll();
						if (e != null) {
							ch.endElement(e.uri, e.localName, e.qName);
							if (any < 0) {
								if (simpleContent) {
									simpleContent = false;
								} else {
									_xsomHelper.endComplex();
								}
							} else if (_stack.size() <= any) {
								if (_stack.size() == any) {
									ch.endPrefixMapping("xsi");
								}
								any = -1;
								_xsomHelper.endAny();
							}
							e = null;
						}
						break;
					case START_ARRAY:
						break;
					case END_ARRAY:
						_xsomHelper.endArray();
						break;
					case KEY_NAME:
						keyName = jsonParser.getString();
						if (attribute = keyName.startsWith(attributePrefix)) {
							keyName = keyName.substring(attributePrefix.length());
						} else if (e != null) {
							ch.startElement(e.uri, e.localName, e.qName, _atts);
							_atts.clear();
							e = null;
						}
						final int i = keyName.indexOf('.');
						if (i >= 0) {
							if (_prefixMap != null) {
								uri = _prefixMap.get(keyName.substring(0, i));
							} else if (VALIDATE_PREFIXES) {
								throw new SAXException("No prefix map provided, but found " + keyName);
							}
							keyName = keyName.substring(i + 1);
						}
						break;
					case VALUE_STRING:
						writeValue(jsonParser.getString(), null);
						break;
					case VALUE_NUMBER:
						writeValue(jsonParser.getString(), jsonParser.isIntegralNumber() ? "xsd:integer" : "xsd:decimal");
						break;
					case VALUE_NULL:
						writeValue(null, null);
						break;
					case VALUE_TRUE:
						writeValue("true", "xsd:boolean");
						break;
					case VALUE_FALSE:
						writeValue("false", "xsd:boolean");
						break;
					default:
						throw new IllegalStateException();
					}
				}
			}
			if (_prefixMap != null) {
				for (Map.Entry<String, String> entry : _prefixMap.entrySet()) {
					ch.endPrefixMapping(entry.getKey());
				}
			}
			if (_createDocumentEvents) ch.endDocument();
		}
	}

}
