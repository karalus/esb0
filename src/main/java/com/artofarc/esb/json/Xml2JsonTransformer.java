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

import java.io.OutputStream;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

import javax.json.Json;
import javax.json.stream.JsonGenerator;
import javax.json.stream.JsonGeneratorFactory;
import javax.xml.XMLConstants;
import javax.xml.bind.DatatypeConverter;
import javax.xml.namespace.QName;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.artofarc.util.XSOMHelper;
import com.sun.xml.xsom.XSAttributeUse;
import com.sun.xml.xsom.XSComplexType;
import com.sun.xml.xsom.XSElementDecl;
import com.sun.xml.xsom.XSSchemaSet;
import com.sun.xml.xsom.XSSimpleType;

public final class Xml2JsonTransformer {

	private static final JsonGeneratorFactory JSON_GENERATOR_FACTORY;

	static {
		HashMap<String, Object> config = new HashMap<>();
		if (Boolean.parseBoolean(System.getProperty("esb0.jsonPrettyPrinting", "true"))) {
			config.put(JsonGenerator.PRETTY_PRINTING, true);
		}
		JSON_GENERATOR_FACTORY = Json.createGeneratorFactory(config);
	}

	private final boolean _includeRoot;
	private final QName _rootElement;
	private final XSComplexType _complexType;
	private final Map<String, String> _nsMap;
	private final String attributePrefix = "@";
	private final String valueWrapper = "value";

	public Xml2JsonTransformer(XSSchemaSet schemaSet, String rootElement, boolean includeRoot, Map<String, String> prefixMap) {
		if (schemaSet == null) {
			schemaSet = XSOMHelper.anySchema;
			rootElement = "root";
		}
		_rootElement = QName.valueOf(rootElement);
		_includeRoot = includeRoot;
		XSElementDecl element = schemaSet.getElementDecl(_rootElement.getNamespaceURI(), _rootElement.getLocalPart());
		_complexType = element.getType().asComplexType();
		_nsMap = prefixMap != null ? Json2XmlTransformer.inverseMap(prefixMap.entrySet()) : null;
	}

	public ContentHandler createTransformerHandler(Writer writer) {
		return new TransformerHandler(JSON_GENERATOR_FACTORY.createGenerator(writer));
	}

	public ContentHandler createTransformerHandler(OutputStream outputStream) {
		return new TransformerHandler(JSON_GENERATOR_FACTORY.createGenerator(outputStream));
	}

	public ContentHandler createTransformerHandler(OutputStream outputStream, Charset charset) {
		return new TransformerHandler(JSON_GENERATOR_FACTORY.createGenerator(outputStream, charset));
	}

	private final class TransformerHandler extends DefaultHandler {

		final JsonGenerator jsonGenerator;
		final StringBuilder _builder = new StringBuilder(128);
		final XSOMHelper _xsomHelper = new XSOMHelper(_complexType);

		boolean root = true, complex;
		String primitiveType, openKey;
		int level, anyLevel = -1;

		TransformerHandler(JsonGenerator jsonGenerator) {
			this.jsonGenerator = jsonGenerator;
		}

		@Override
		public void startDocument() {
			jsonGenerator.writeStartObject();
		}

		@Override
		public void endDocument() {
			if (_includeRoot) jsonGenerator.writeEnd();
			jsonGenerator.close();
		}

		@Override
		public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
			if (openKey != null) {
				debug("start deferred " + openKey);
				jsonGenerator.writeStartObject(openKey);
				openKey = null;
			}
			int attsLength = atts.getLength();
			if (root) {
				root = false;
				if (!_includeRoot) {
					++level;
					return;
				}
				complex = true;
			} else {
				_xsomHelper.matchElement(uri, localName);
				if (_xsomHelper.isLastElementAny()) {
					int index = atts.getIndex(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type");
					if (index >= 0) {
						String value = atts.getValue(index);
						// TODO: Check whether it is really primitive
						primitiveType = value.substring(value.indexOf(':') + 1);
						--attsLength;
					}
					if (anyLevel < 0) {
						if (!_includeRoot && level == 1) {
							anyLevel = 0;
						} else {
							anyLevel = level;
						}
					}
				} else {
					complex = _xsomHelper.isLastElementComplex();
					final XSSimpleType simpleType = _xsomHelper.getSimpleType();
					primitiveType = simpleType != null ? XSOMHelper.getJsonType(simpleType) : null;
				}
			}
			String key = localName;
			if (_nsMap != null) {
				String prefix = _nsMap.get(uri);
				if (prefix != null && prefix.length() > 0) {
					key = prefix + '.' + localName;
				}
			}
			if ((anyLevel < 0 || level == anyLevel) && _xsomHelper.isEndArray()) {
				debug("endArray on " + key);
				jsonGenerator.writeEnd();
				_xsomHelper.endArray();
			}
			if (anyLevel < 0 && _xsomHelper.isStartArray()) {
				debug("startArray " + key);
				jsonGenerator.writeStartArray(key);
			} else {
				if (anyLevel >= 0 || !_xsomHelper.isMiddleOfArray()) {
					openKey = key;
				}
			}
			final int index = atts.getIndex(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "nil");
			if (index >= 0 && DatatypeConverter.parseBoolean(atts.getValue(index))) {
				primitiveType = "nil";
				--attsLength;
			}
			if (attsLength > 0 || complex && anyLevel < 0) {
				debug("start " + key);
				if (openKey != null) {
					jsonGenerator.writeStartObject(openKey);
				} else {
					jsonGenerator.writeStartObject();
				}
				for (int i = 0; i < attsLength; ++i) {
					XSAttributeUse attributeUse = _xsomHelper.getAttributeUse(atts.getURI(i), atts.getLocalName(i));
					String type = attributeUse != null ? XSOMHelper.getJsonType(attributeUse.getDecl().getType()) : "string";
					writeKeyValue(attributePrefix + atts.getLocalName(i), atts.getValue(i), type);
				}
				openKey = null;
				if (primitiveType != null) {
					openKey = valueWrapper;
				}
			}
			++level;
		}
		
		@Override
		public void endElement(String uri, String localName, String qName) {
			if (openKey != null && primitiveType == null) {
				primitiveType = "string";
			}
			if (primitiveType != null) {
				if (openKey != null) {
					writeKeyValue(openKey, _builder.toString(), primitiveType);
				} else {
					writeValue(_builder.toString(), primitiveType);
				}
			} else {
				if (anyLevel < 0) {
					if (_xsomHelper.isInArray()) {
						debug("endArray+ on " + localName);
						jsonGenerator.writeEnd();
						_xsomHelper.endArray();
					}
				} 
				if (_builder.length() > 0) {
					if (anyLevel < 0 ) {
						if (_xsomHelper.getCurrentComplexType().isMixed()) {
							jsonGenerator.writeStartArray(valueWrapper);
							jsonGenerator.write(_builder.toString());
							jsonGenerator.writeEnd();
						}
					} else {
						jsonGenerator.write(valueWrapper, _builder.toString());
					}
				}
				_xsomHelper.endComplex();
			}
			if (complex || primitiveType == null) {
				debug("end " + localName);
				jsonGenerator.writeEnd();
			}
			if (anyLevel == --level) {
				anyLevel = -1;
				_xsomHelper.endAny();
			}
			primitiveType = null;
			_builder.setLength(0);
			openKey = null;
		}

		@Override
		public void characters(char[] ch, int start, int length) {
			_builder.append(ch, start, length);
		}

		private void writeKeyValue(String key, String s, String primitiveType) {
			switch (primitiveType) {
			case "int":
				// is faster
				jsonGenerator.write(key, DatatypeConverter.parseInt(s));
				break;
			case "long":
				jsonGenerator.write(key, Long.parseLong(s));
				break;
			case "integer":
				jsonGenerator.write(key, new BigInteger(s));
				break;
			case "decimal":
				jsonGenerator.write(key, new BigDecimal(s));
				break;
			case "float":
			case "double":
				// covers NaN and INF, still writing will fail
				jsonGenerator.write(key, DatatypeConverter.parseDouble(s));
				break;
			case "boolean":
				// covers '1' and '0'
				jsonGenerator.write(key, DatatypeConverter.parseBoolean(s));
				break;
			case "nil":
				jsonGenerator.writeNull(key);
				break;
			default:
				jsonGenerator.write(key, s);
				break;
			}
		}

		private void writeValue(String s, String primitiveType) {
			switch (primitiveType) {
			case "int":
				// is faster
				jsonGenerator.write(DatatypeConverter.parseInt(s));
				break;
			case "long":
				jsonGenerator.write(Long.parseLong(s));
				break;
			case "integer":
				jsonGenerator.write(new BigInteger(s));
				break;
			case "decimal":
				jsonGenerator.write(new BigDecimal(s));
				break;
			case "float":
			case "double":
				// covers NaN and INF, still writing will fail
				jsonGenerator.write(DatatypeConverter.parseDouble(s));
				break;
			case "boolean":
				// covers '1' and '0'
				jsonGenerator.write(DatatypeConverter.parseBoolean(s));
				break;
			case "nil":
				jsonGenerator.writeNull();
				break;
			default:
				jsonGenerator.write(s);
				break;
			}
		}
		
	}

	private static void debug(String s) {
//		System.out.println(s);
	}

}
