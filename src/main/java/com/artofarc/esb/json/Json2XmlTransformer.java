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
import java.util.Map;

import javax.json.stream.JsonParser;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;

import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import com.artofarc.util.Collections;
import com.artofarc.util.JsonFactoryHelper;
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

	private final XSSchemaSet _schemaSet;
	private final boolean _createDocumentEvents;
	private final boolean _includeRoot;
	private final String _rootUri, _rootName;
	private final XSElementDecl elementDecl;
	private final XSComplexType _complexType;
	private final Map<String, String> _prefixMap, _nsMap;
	private final String attributePrefix = "@";
	private final String valueWrapper = "value";

	public Json2XmlTransformer(XSSchemaSet schemaSet, boolean createDocumentEvents, String rootElement, String type, boolean includeRoot, Map<String, String> prefixMap) {
		if (schemaSet == null) {
			_schemaSet = XSOMHelper.anySchema;
			_rootUri = XMLConstants.NULL_NS_URI;
			_rootName = "root";
			elementDecl = _schemaSet.getElementDecl(_rootUri, _rootName);
			_complexType = _schemaSet.getElementDecl(_rootUri, _rootName).getType().asComplexType();
		} else {
			_schemaSet = schemaSet;
			if (rootElement != null) {
				QName _rootElement = QName.valueOf(rootElement);
				_rootUri = _rootElement.getNamespaceURI();
				_rootName = _rootElement.getLocalPart();
				elementDecl = schemaSet.getElementDecl(_rootUri, _rootName);
				if (elementDecl != null) {
					_complexType = elementDecl.getType().asComplexType();
				} else if (type != null) {
					QName _type = QName.valueOf(type);
					_complexType = schemaSet.getComplexType(_type.getNamespaceURI(), _type.getLocalPart());
				} else {
					_complexType = null;
				}
			} else {
				_rootUri = null;
				_rootName = null;
				elementDecl = null;
				_complexType = null;
			}
		}
		_includeRoot = includeRoot;
		_prefixMap = prefixMap;
		_nsMap = prefixMap != null ? Collections.inverseMap(prefixMap.entrySet()) : null;
		_createDocumentEvents = createDocumentEvents;
	}

	public XMLFilterBase createParser() {
		return new Parser();
	}

	private static final class Source implements Locator {

		final JsonParser jsonParser;
		final InputSource _source;

		Source(InputSource source) throws SAXException {
			if (source.getByteStream() != null) {
				if (source.getEncoding() != null) {
					jsonParser = JsonFactoryHelper.JSON_PARSER_FACTORY.createParser(source.getByteStream(), Charset.forName(source.getEncoding()));
				} else {
					jsonParser = JsonFactoryHelper.JSON_PARSER_FACTORY.createParser(source.getByteStream());
				}
			} else if (source.getCharacterStream() != null) {
				jsonParser = JsonFactoryHelper.JSON_PARSER_FACTORY.createParser(source.getCharacterStream());
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

		final String uri, localName, qName;

		Element(String uri, String localName, String qName) {
			this.uri = uri;
			this.localName = localName;
			this.qName = qName;
		}
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

		final ArrayDeque<Element> _stack = new ArrayDeque<>();
		final AttributesImpl _atts = new AttributesImpl();
		final ArrayDeque<String> _arrays = new ArrayDeque<>();

		XSOMHelper xsomHelper;
		String keyName, uri;
		boolean attribute, simpleContent;
		int any = -1;

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
						if (keyName == null && (xsomHelper == null || !xsomHelper.isInArray())) {
							// assume we are on root node
							if (_includeRoot) {
								continue;
							} else {
								keyName = _rootName;
								uri = _rootUri;
								xsomHelper = new XSOMHelper(_complexType, _schemaSet.getElementDecl(uri, keyName));
							}
						} else {
							if (_includeRoot && _stack.isEmpty()) {
								if (_rootName != null) {
									if (!_rootName.equals(keyName)) {
										throw new SAXException("JSON must start with: " + _rootName);
									}
									uri = _rootUri;
								} else {
									if (_prefixMap != null) {
										uri = _prefixMap.get(XMLConstants.DEFAULT_NS_PREFIX);
									}
									if (uri == null) {
										uri = XMLConstants.NULL_NS_URI;
									}
								}
								xsomHelper = new XSOMHelper(_complexType, _schemaSet.getElementDecl(uri, keyName));
							} else {
								if (keyName == null) {
									keyName = _arrays.peek();
								}
								XSTerm term = xsomHelper.matchElement(uri, keyName);
								uri = term.apply(XSOMHelper.GetNamespace);
								if (xsomHelper.isLastElementAny()) {
									if (any < 0) {
										any = _stack.size();
										ch.startPrefixMapping("xsi", XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI);
									} 
								} else {
									if (xsomHelper.getComplexType() == null) {
										throw new SAXException("Expected complex type: " + new QName(uri, keyName));
									}
									simpleContent = xsomHelper.getSimpleType() != null;
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
									xsomHelper.endComplex();
								}
							} else if (_stack.size() <= any) {
								if (_stack.size() < any || !xsomHelper.isInArray()) {
									ch.endPrefixMapping("xsi");
									any = -1;
									xsomHelper.endAny();
								}
							}
							e = null;
						}
						break;
					case START_ARRAY:
						_arrays.push(keyName);
						break;
					case END_ARRAY:
						_arrays.pop();
						if (any < 0) {
							xsomHelper.endArray();
						}
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

		private void writeValue(String value, String type) throws SAXException {
			if (attribute) {
				XSAttributeUse attributeUse = xsomHelper.getAttributeUse(uri, keyName);
				if (attributeUse != null) {
					uri = attributeUse.getDecl().getTargetNamespace();
				} else {
					XSWildcard wildcard = xsomHelper.getAttributeWildcard();
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
				if (keyName == null) {
					keyName = _arrays.peek();
				}
				if ((simpleContent || any >= 0) && valueWrapper.equals(keyName)) {
					simpleContent = true;
					ch.characters(value.toCharArray(), 0, value.length());
				} else if (xsomHelper.getCurrentComplexType().isMixed() && valueWrapper.equals(keyName)) {
					ch.characters(value.toCharArray(), 0, value.length());
				} else {
					uri = xsomHelper.matchElement(uri, keyName).apply(XSOMHelper.GetNamespace);
					if (xsomHelper.isLastElementAny()) {
						if (any < 0) {
							any = _stack.size();
						}
					} else {
						if (xsomHelper.getSimpleType() == null) {
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
					} else if (any >= 0 || xsomHelper.matchElement(uri, keyName).asElementDecl().isNillable()) {
						ch.startPrefixMapping("xsi", XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI);
						_atts.addAttribute(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "nil", "xsi:nil", "CDATA", "true");
						ch.startElement(uri, keyName, qName, _atts);
						ch.endElement(uri, keyName, qName);
						_atts.clear();
						ch.endPrefixMapping("xsi");
					}
					if (!xsomHelper.isInArray()) {
						if (_stack.size() == any) {
							any = -1;
							xsomHelper.endAny();
						}
					}
				}
			}
			keyName = null;
			uri = null;
		}
	}

}
