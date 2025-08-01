/*
 * Copyright 2022 Andre Karalus
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
package com.artofarc.esb.json;

import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.regex.Pattern;

import javax.json.stream.JsonGenerator;
import javax.xml.XMLConstants;
import javax.xml.bind.DatatypeConverter;
import javax.xml.namespace.QName;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import com.artofarc.util.AttributesHelper;
import com.artofarc.util.JsonFactoryHelper;
import com.artofarc.util.NamespaceMap;
import com.artofarc.util.PrefixHandler;
import com.artofarc.util.XSOMHelper;
import com.sun.xml.xsom.*;

public final class Xml2JsonTransformer {

	/**
	 * @see https://json-schema.org/draft/2019-09/json-schema-validation.html#rfc.section.7.3.1
	 */
	public static final boolean JSON_OMIT_TZ_FROM_DATE = Boolean.parseBoolean(System.getProperty("esb0.json.omitTZfromDate", "true"));

	private static final Pattern whitespacePattern = Pattern.compile("\\s+");

	private final XSSchemaSet _schemaSet;
	private final XSType _type;
	private final boolean _includeRoot, _wrapperAsArrayName;
	private final NamespaceMap _namespaceMap;
	private final String attributePrefix = "@";
	private final String valueWrapper = "value";

	public Xml2JsonTransformer(XSSchemaSet schemaSet, String typeQN, boolean includeRoot, boolean wrapperAsArrayName, Map<String, String> prefixMap) {
		_schemaSet = schemaSet != null ? schemaSet : XSOMHelper.anySchema;
		if (typeQN != null) {
			QName type = QName.valueOf(typeQN);
			_type = schemaSet.getType(type.getNamespaceURI(), type.getLocalPart());
		} else {
			_type = schemaSet != null ? null : _schemaSet.getAnyType();
		}
		_includeRoot = includeRoot;
		_wrapperAsArrayName = wrapperAsArrayName;
		_namespaceMap = prefixMap != null ? new NamespaceMap(prefixMap) : null;
	}

	private String prependPrefix(String uri, String localName) {
		if (_namespaceMap != null) {
			String prefix = _namespaceMap.getPrefix(uri);
			if (prefix != null && prefix.length() > 0) {
				return prefix + '.' + localName;
			}
		}
		return localName;
	}

	public ContentHandler createTransformerHandler(JsonGenerator jsonGenerator) {
		return new TransformerHandler(jsonGenerator);
	}

	public ContentHandler createTransformerHandler(Writer writer) {
		return new TransformerHandler(JsonFactoryHelper.JSON_GENERATOR_FACTORY.createGenerator(writer));
	}

	private final class TransformerHandler extends PrefixHandler {

		final JsonGenerator jsonGenerator;
		final StringBuilder _builder = new StringBuilder(128);
		final ArrayDeque<Integer> ignoreLevel = new ArrayDeque<>();

		XSOMHelper xsomHelper;
		boolean root = true, complex, simpleList, unopened;
		String primitiveType, openKey;
		int level, anyLevel = -1;

		TransformerHandler(JsonGenerator jsonGenerator) {
			this.jsonGenerator = jsonGenerator;
		}

		@Override
		public void endDocument() {
			if (_builder.length() > 0) {
				// XML is invalid but this avoids "Generating incomplete JSON"
				jsonGenerator.write(_builder.toString());
			}
			if (_includeRoot) jsonGenerator.writeEnd();
			jsonGenerator.close();
		}

		private String getJsonType(String xsiType) throws SAXException {
			final int i = xsiType.indexOf(':');
			final String namespace = getNamespace(i < 0 ? XMLConstants.DEFAULT_NS_PREFIX : xsiType.substring(0, i));
			if (namespace == null) {
				throw new SAXException("Namespace not found for xsi:type " + xsiType);
			}
			final XSSchema schema = _schemaSet.getSchema(namespace);
			if (schema == null) {
				throw new SAXException("Schema not found for namespace " + namespace);
			}
			final XSType type = schema.getType(i < 0 ? xsiType : xsiType.substring(i + 1));
			if (type == null) {
				throw new SAXException("xsi:type could not be resolved " + xsiType);
			}
			if (type.isSimpleType()) {
				final XSSimpleType simpleType = type.asSimpleType();
				final XSSimpleType itemType = XSOMHelper.getItemType(simpleType);
				if (simpleList = itemType != null) {
					return XSOMHelper.getJsonType(itemType);
				} else {
					return XSOMHelper.getJsonType(simpleType);
				}
			} else {
				throw new SAXException("xsi:type with complexType not supported, yet");
			}
		}

		private boolean isWrapped() {
			final Integer ignore = ignoreLevel.peek();
			return ignore != null && level == ignore;
		}

		@Override
		public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
			final AttributesHelper helper = new AttributesHelper(atts);
			_builder.setLength(0);
			if (root) {
				root = false;
				if (_type != null && _type.isSimpleType()) {
					primitiveType = XSOMHelper.getJsonType(_type.asSimpleType());
				} else {
					xsomHelper = new XSOMHelper((XSComplexType) _type, _schemaSet.getElementDecl(uri, localName));
					if (xsomHelper.getComplexType() == _schemaSet.getAnyType()) {
						anyLevel = 0;
						unopened = true;
					} else if (!_includeRoot && _wrapperAsArrayName && xsomHelper.getWrappedElement() != null) {
						jsonGenerator.writeStartArray();
						ignoreLevel.push(1);
					} else {
						jsonGenerator.writeStartObject();
					}
				}
				if (!_includeRoot) {
					level = 1;
					extractType(helper);
					extractSimpleType();
					writeAttributes(helper);
					if (primitiveType != null && helper.getLength() > 0) {
						openKey = valueWrapper;
					}
					return;
				}
				complex = true;
			} else {
				if (xsomHelper == null) {
					throw new SAXException("Expecting text, but got element " + localName);
				}
				if (unopened) {
					jsonGenerator.writeStartObject();
					unopened = false;
				}
				xsomHelper.matchElement(uri, localName);
				extractType(helper);
				if (xsomHelper.isLastElementAny()) {
					if (anyLevel < 0) {
						if (!_includeRoot && level == 1) {
							anyLevel = 0;
						} else {
							anyLevel = level;
						}
					}
				} else {
					complex = xsomHelper.getComplexType() != null;
					extractSimpleType();
				}
			}
			if ((anyLevel < 0 || level == anyLevel) && xsomHelper.isEndArray()) {
				jsonGenerator.writeEnd();
				xsomHelper.endArray();
			}
			if (anyLevel < 0 && xsomHelper.isStartArray()) {
				if (!isWrapped()) {
					jsonGenerator.writeStartArray(openKey != null ? openKey : prependPrefix(uri, localName));
				}
				openKey = null;
			} else {
				if (openKey != null) {
					jsonGenerator.writeStartObject(openKey);
					openKey = null;
				}
				if (anyLevel >= 0 || !xsomHelper.isMiddleOfArray()) {
					openKey = prependPrefix(uri, localName);
				}
			}
			extractNil(helper);
			++level;
			if (helper.getLength() > 0 || complex && anyLevel < 0 && primitiveType != "nil") {
				if (openKey != null) {
					if (_wrapperAsArrayName && xsomHelper.getWrappedElement() != null) {
						jsonGenerator.writeStartArray(openKey);
						openKey = null;
						ignoreLevel.push(level);
					} else {
						jsonGenerator.writeStartObject(openKey);
						openKey = null;
					}
				} else {
					jsonGenerator.writeStartObject();
				}
				writeAttributes(helper);
				if (primitiveType != null) {
					openKey = valueWrapper;
				}
			}
		}

		@Override
		public void endElement(String uri, String localName, String qName) {
			if (openKey != null) {
				if (primitiveType == null) {
					primitiveType = "string";
				}
				if (openKey != valueWrapper) {
					complex = false;
				}
			}
			if (primitiveType != null) {
				if (simpleList) {
					if (openKey != null) {
						jsonGenerator.writeStartArray(openKey);
					} else {
						jsonGenerator.writeStartArray();
					}
					if (_builder.length() > 0) {
						for (String value : whitespacePattern.split(_builder)) {
							writeValue(value, primitiveType);
						}
					}
					jsonGenerator.writeEnd();
				} else {
					if (openKey != null) {
						writeKeyValue(openKey, _builder.toString(), primitiveType);
					} else {
						writeValue(_builder.toString(), primitiveType);
					}
				}
			} else {
				if (anyLevel < 0) {
					if (xsomHelper.isInArray()) {
						jsonGenerator.writeEnd();
						xsomHelper.endArray();
					} else if (isWrapped()) {
						jsonGenerator.writeEnd();
					}
				}
				if (_builder.length() > 0) {
					if (anyLevel < 0) {
						final XSComplexType currentComplexType = xsomHelper.getCurrentComplexType();
						if (currentComplexType != null && currentComplexType.isMixed()) {
							jsonGenerator.writeStartArray(valueWrapper);
							jsonGenerator.write(_builder.toString());
							jsonGenerator.writeEnd();
						}
					} else {
						String text = _builder.toString();
						if (text.trim().length() > 0) {
							if (unopened) {
								jsonGenerator.write(text);
								primitiveType = "string";
							} else {
								jsonGenerator.write(valueWrapper, text);
							}
						}
					}
				}
				if (anyLevel < 0 || level == anyLevel) {
					xsomHelper.endComplex();
					if (level == xsomHelper.getLevel()) {
						if (level > 1 && xsomHelper.isInArray()) {
							jsonGenerator.writeEnd();
							xsomHelper.endArray();
						}
						xsomHelper.endAny();
					}
				}
			}
			if (complex || primitiveType == null || openKey == valueWrapper) {
				if (isWrapped()) {
					ignoreLevel.pop();
				} else {
					jsonGenerator.writeEnd();
				}
			}
			if (anyLevel == level--) {
				anyLevel = -1;
				if (xsomHelper.getComplexType() != _schemaSet.getAnyType()) {
					xsomHelper.endAny();
				}
			}
			primitiveType = null;
			_builder.setLength(0);
			openKey = null;
		}

		@Override
		public void characters(char[] ch, int start, int length) {
			_builder.append(ch, start, length);
		}

		private void extractType(AttributesHelper helper) throws SAXException {
			if (helper.getType() != null) {
				primitiveType = getJsonType(helper.getType());
			}
		}

		private void extractSimpleType() {
			if (primitiveType == null) {
				XSSimpleType simpleType = xsomHelper.getSimpleType();
				if (simpleType != null) {
					if (simpleType.isUnion()) {
						simpleType = XSOMHelper.getDefaultMemberType(simpleType.asUnion());
					}
					final XSSimpleType itemType = XSOMHelper.getItemType(simpleType);
					if (simpleList = itemType != null) {
						primitiveType = XSOMHelper.getJsonType(itemType);
					} else {
						primitiveType = XSOMHelper.getJsonType(simpleType);
					}
				}
			}
		}

		private void extractNil(AttributesHelper helper) throws SAXException {
			if (helper.getNil() != null && DatatypeConverter.parseBoolean(helper.getNil())) {
				if (helper.getLength() > 0) {
					// https://stackoverflow.com/questions/27555077/specify-attributes-on-a-nil-xml-element
					throw new SAXException("A complex element being nil should not have additional attributes");
				}
				primitiveType = "nil";
				if (complex) {
					xsomHelper.endComplex();
				}
			}
		}

		private void writeAttributes(AttributesHelper helper) {
			if (helper.getLength() > 0) {
				helper.forEach((u, l, v) -> {
					XSAttributeUse attributeUse = xsomHelper.getAttributeUse(u, l);
					String type = attributeUse != null ? XSOMHelper.getJsonType(attributeUse.getDecl().getType()) : "string";
					writeKeyValue(attributePrefix + prependPrefix(u, l), v, type);
				});
			}
		}

		private void writeKeyValue(String key, String s, String primitiveType) {
			switch (primitiveType) {
			case "int":
				// is faster
				jsonGenerator.write(key, DatatypeConverter.parseInt(s));
				break;
			// Might cause trouble: https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Number/MAX_SAFE_INTEGER
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
			case "date":
				jsonGenerator.write(key, omitTZfromDate(s));
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
			case "date":
				jsonGenerator.write(omitTZfromDate(s));
				break;
			default:
				jsonGenerator.write(s);
				break;
			}
		}

	}

	public static String omitTZfromDate(String date) {
		if (JSON_OMIT_TZ_FROM_DATE) {
			if (date.charAt(0) == '-') {
				throw new IllegalArgumentException("year must not be negative " + date);
			}
			date = date.substring(0, 10);
		}
		return date;
	}

}
