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
import java.util.Set;

import javax.json.*;
import javax.json.stream.JsonParser;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;

import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import com.artofarc.util.Collections;
import com.artofarc.util.JsonFactoryHelper;
import com.artofarc.util.XMLParserBase;
import com.artofarc.util.XSOMHelper;
import com.sun.xml.xsom.*;

public final class Json2XmlTransformer {

	private static final boolean VALIDATE_PREFIXES = false;

	private final XSSchemaSet _schemaSet;
	private final boolean _createDocumentEvents;
	private final boolean _includeRoot;
	private final String _rootUri, _rootName;
	private final XSComplexType _complexType;
	private final Map<String, String> _prefixMap, _nsMap;
	private final String attributePrefix = "@";
	private final String valueWrapper = "value";

	public Json2XmlTransformer(XSSchemaSet schemaSet, boolean createDocumentEvents, String rootElement, String type, boolean includeRoot, Map<String, String> prefixMap) {
		if (schemaSet == null) {
			_schemaSet = XSOMHelper.anySchema;
			_rootUri = XMLConstants.NULL_NS_URI;
			_rootName = "root";
			_complexType = _schemaSet.getElementDecl(_rootUri, _rootName).getType().asComplexType();
		} else {
			_schemaSet = schemaSet;
			if (rootElement != null) {
				QName _rootElement = QName.valueOf(rootElement);
				_rootUri = _rootElement.getNamespaceURI();
				_rootName = _rootElement.getLocalPart();
				XSElementDecl elementDecl = schemaSet.getElementDecl(_rootUri, _rootName);
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
				_complexType = null;
			}
		}
		_includeRoot = includeRoot;
		_prefixMap = prefixMap;
		_nsMap = prefixMap != null ? Collections.inverseMap(prefixMap.entrySet()) : null;
		_createDocumentEvents = createDocumentEvents;
	}

	public XMLParserBase createStreamingParser() {
		return new StreamingParser();
	}

	public XMLParserBase createParser() {
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

		@Override
		public String toString() {
			return new QName(uri, localName).toString();
		}
	}

	private abstract class AbstractParser extends XMLParserBase {

		final AttributesImpl _atts = new AttributesImpl();

		XSOMHelper xsomHelper;
		String keyName, uri;
		int any = -1;

		AbstractParser() {
			super(_createDocumentEvents, _prefixMap, _nsMap);
		}

		void addAttribute(String value) throws SAXException {
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
		}
	}

	private final class StreamingParser extends AbstractParser {

		final ArrayDeque<Element> _stack = new ArrayDeque<>();
		final ArrayDeque<String> _arrays = new ArrayDeque<>();

		boolean attribute, simpleContent;

		@Override
		public void parse(InputSource inputSource) throws SAXException {
			Source source = new Source(inputSource);
			setDocumentLocator(source);
			startDocument();
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
									uri = getDefaultUri();
								}
								xsomHelper = new XSOMHelper(_complexType, _schemaSet.getElementDecl(uri, keyName));
							} else {
								if (keyName == null) {
									keyName = _arrays.peek();
								}
								final XSTerm term = xsomHelper.matchElement(uri, keyName);
								uri = term.apply(XSOMHelper.GetNamespace);
								if (xsomHelper.isLastElementAny()) {
									if (any < 0) {
										any = _stack.size();
										startPrefixMapping("xsi", XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI);
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
						if (e != null) {
							startElement(e.uri, e.localName, e.qName, _atts);
						}
						e = _stack.poll();
						if (e != null) {
							endElement(e.uri, e.localName, e.qName);
							if (any < 0) {
								if (simpleContent) {
									simpleContent = false;
								} else {
									xsomHelper.endComplex();
								}
							} else if (_stack.size() <= any) {
								if (_stack.size() < any || !xsomHelper.isInArray()) {
									endPrefixMapping("xsi");
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
							startElement(e.uri, e.localName, e.qName, _atts);
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
			endDocument();
		}

		private void writeValue(String value, String type) throws SAXException {
			if (attribute) {
				addAttribute(value);
			} else {
				if (keyName == null) {
					keyName = _arrays.peek();
				}
				if ((simpleContent || any >= 0) && valueWrapper.equals(keyName)) {
					simpleContent = true;
					characters(value);
				} else if (xsomHelper.getCurrentComplexType().isMixed() && valueWrapper.equals(keyName)) {
					characters(value);
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
								startPrefixMapping("xsi", XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI);
							}
							_atts.addAttribute(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type", "xsi:type", "CDATA", type);
						}
						startElement(uri, keyName, qName, _atts);
						characters(value);
						endElement(uri, keyName, qName);
						if (any >= 0 && type != null) {
							_atts.clear();
							if(any == _stack.size()) {
								endPrefixMapping("xsi");
							}
						}
					} else if (any >= 0 || xsomHelper.matchElement(uri, keyName).asElementDecl().isNillable()) {
						startPrefixMapping("xsi", XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI);
						_atts.addAttribute(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "nil", "xsi:nil", "CDATA", "true");
						startElement(uri, keyName, qName, _atts);
						endElement(uri, keyName, qName);
						_atts.clear();
						endPrefixMapping("xsi");
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

	private static JsonObject readObject(InputSource source) throws SAXException {
		JsonReader jsonReader;
		if (source.getByteStream() != null) {
			if (source.getEncoding() != null) {
				jsonReader = JsonFactoryHelper.JSON_READER_FACTORY.createReader(source.getByteStream(), Charset.forName(source.getEncoding()));
			} else {
				jsonReader = JsonFactoryHelper.JSON_READER_FACTORY.createReader(source.getByteStream());
			}
		} else if (source.getCharacterStream() != null) {
			jsonReader = JsonFactoryHelper.JSON_READER_FACTORY.createReader(source.getCharacterStream());
		} else {
			throw new SAXException("InputSource must provide a stream");
		}
		JsonObject jsonObject = jsonReader.readObject();
		jsonReader.close();
		return jsonObject;
	}

	private final class Parser extends AbstractParser {

		String primitiveType;

		@Override
		public void parse(InputSource inputSource) throws SAXException {
			JsonObject jsonObject = readObject(inputSource);
			startDocument();
			if (_includeRoot) {
				Set<String> keySet = jsonObject.keySet();
				if (keySet.size() != 1) {
					throw new SAXException("JSON with root must consist of exactly one value");
				}
				keyName = keySet.iterator().next();
				if (_rootName != null) {
					if (!_rootName.equals(keyName)) {
						throw new SAXException("JSON must start with: " + _rootName);
					}
					uri = _rootUri;
				} else {
					uri = getDefaultUri();
				}
				jsonObject = jsonObject.getJsonObject(keyName);
			} else {
				keyName = _rootName;
				uri = _rootUri;
			}
			xsomHelper = new XSOMHelper(_complexType, _schemaSet.getElementDecl(uri, keyName));
			parse(new Element(uri, keyName, createQName(uri, keyName)), jsonObject);
			endDocument();
		}

		private void parse(Element e, JsonObject jsonObject) throws SAXException {
			uri = null;
			for (String key : jsonObject.keySet()) {
				if (key.startsWith(attributePrefix)) {
					keyName = key.substring(attributePrefix.length());
					addAttribute(getString(jsonObject.get(key)));
				}				
			}
			if (xsomHelper.getSimpleType() != null) {
				parse(e, getJsonValue(jsonObject, valueWrapper));
				_atts.clear();
			} else {
				boolean mixed = xsomHelper.getComplexType().isMixed();
				startElement(e.uri, e.localName, e.qName, _atts);
				_atts.clear();
				for (int level = xsomHelper.getLevel();;) {
					final XSTerm term = xsomHelper.nextElement();
					if (level >= xsomHelper.getLevel()) {
						if (term != null) {
							xsomHelper.push(term);
						}
						break;
					}
					if (xsomHelper.isLastElementAny()) {
						xsomHelper.endAny();
					} else {
						uri = null;
						keyName = term.apply(XSOMHelper.GetName);
						final JsonValue jsonValue = getJsonValue(jsonObject, keyName);
						if (jsonValue != null) {
							if (uri == null) {
								uri = term.apply(XSOMHelper.GetNamespace);
							}
							parse(new Element(uri, keyName, createQName(uri, keyName)), jsonValue);
						} else {
							if (xsomHelper.isLastElementRequired()) {
								throw new SAXException("Missing required element: " + new QName(uri, keyName));
							}
							if (xsomHelper.getComplexType() != null) {
								xsomHelper.endComplex();
							}
						}
					}
				}
				if (mixed) {
					JsonArray jsonArray = (JsonArray) getJsonValue(jsonObject, valueWrapper);
					if (jsonArray != null) {
						for (JsonValue jsonValue : jsonArray) {
							JsonString jsonString = (JsonString) jsonValue;
							characters(jsonString.getString());
						}
					}
				}
				endElement(e.uri, e.localName, e.qName);
			}
		}

		private void parse(Element e, JsonValue jsonValue) throws SAXException {
			switch(jsonValue.getValueType()) {
			case OBJECT:
				parse(e, jsonValue.asJsonObject());
				break;
			case ARRAY:
				if (!xsomHelper.isLastElementRepeated()) {
					throw new SAXException("Array not expected for " + e);
				}
				xsomHelper.startArray();
				for (JsonValue jsonValue2 : jsonValue.asJsonArray()) {
					parse(e, jsonValue2);
					if (xsomHelper.poll() != null) {
						xsomHelper.startArray();
					}
				}
				xsomHelper.endArray();
				break;
			case NULL:
				// nillable?
				break;
			default:
				String value = getString(jsonValue);
				if (any >= 0 && primitiveType != null) {
					_atts.addAttribute(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type", "xsi:type", "CDATA", primitiveType);
				}
				startElement(e.uri, e.localName, e.qName, _atts);
				characters(value);
				endElement(e.uri, e.localName, e.qName);
				if (any >= 0 && primitiveType != null) {
					_atts.clear();
				}
				break;
			}
		}

		private JsonValue getJsonValue(JsonObject jsonObject, String key) {
			JsonValue jsonValue = jsonObject.get(key);
			if (jsonValue == null && _prefixMap != null) {
				for (String prefix : _prefixMap.keySet()) {
					if (prefix.length() > 0) {
						jsonValue = jsonObject.get(prefix + '.' + key);
						if (jsonValue != null) {
							uri = _prefixMap.get(prefix);
							break;
						}
					}
				}
			}
			return jsonValue;
		}

		private String getString(JsonValue jsonValue) {
			switch(jsonValue.getValueType()) {
			case STRING:
				JsonString jsonString = (JsonString) jsonValue;
				primitiveType = null;
				return jsonString.getString();
			case NUMBER:
				JsonNumber jsonNumber = (JsonNumber) jsonValue;
				if (jsonNumber.isIntegral()) {
					primitiveType = "xsd:integer";
					return jsonNumber.bigIntegerValue().toString();
				} else {
					primitiveType = "xsd:decimal";
					return jsonNumber.bigDecimalValue().toString();
				}
			case TRUE:
				primitiveType = "xsd:boolean";
				return "true";
			case FALSE:
				primitiveType = "xsd:boolean";
				return "false";
			default:
				throw new IllegalArgumentException("Not atomic " + jsonValue.getValueType());
			}
		}
	}
	
}
