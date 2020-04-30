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
package com.artofarc.esb;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.json.stream.JsonGenerator;
import javax.xml.namespace.QName;

import org.xml.sax.SAXException;

import com.artofarc.util.Collections;
import com.artofarc.util.JAXPFactoryHelper;
import com.artofarc.util.JsonFactoryHelper;
import com.artofarc.util.XSOMHelper;
import com.sun.xml.xsom.XSAttributeDecl;
import com.sun.xml.xsom.XSAttributeUse;
import com.sun.xml.xsom.XSComplexType;
import com.sun.xml.xsom.XSFacet;
import com.sun.xml.xsom.XSSchemaSet;
import com.sun.xml.xsom.XSSimpleType;
import com.sun.xml.xsom.XSTerm;
import com.sun.xml.xsom.parser.XSOMParser;

public final class JsonSchemaGenerator {

	private final XSSchemaSet _schemaSet;
	private final Map<String, String> _nsMap;
	private final String attributePrefix = "@";
	private final String valueWrapper = "value";

	public JsonSchemaGenerator(XSSchemaSet schemaSet, Map<String, String> prefixMap) {
		_schemaSet = schemaSet;
		_nsMap = prefixMap != null ? Collections.inverseMap(prefixMap.entrySet()) : null;
	}

	public void generate(String type, JsonGenerator jsonGenerator) {
		QName _type = QName.valueOf(type);
		XSComplexType complexType = _schemaSet.getComplexType(_type.getNamespaceURI(), _type.getLocalPart());
		if (complexType == null) {
			throw new IllegalArgumentException("Could not find type " + type);
		}
		XSOMHelper xsomHelper = new XSOMHelper(complexType, null);
		jsonGenerator.writeStartObject();
		// jsonGenerator.write("$schema", "http://json-schema.org/draft-04/schema#");
		try {
			generateObject(xsomHelper, jsonGenerator);
		} catch (SAXException e) {
			throw new RuntimeException(e);
		}
		jsonGenerator.writeEnd();
	}

	private void generateObject(XSOMHelper xsomHelper, JsonGenerator jsonGenerator) throws SAXException {
		List<String> required = new ArrayList<>();
		boolean any = false, mixed = xsomHelper.getComplexType().isMixed();
		jsonGenerator.write("type", "object");
		if (xsomHelper.getAttributeWildcard() != null) {
			jsonGenerator.writeStartObject("patternProperties");
			jsonGenerator.writeStartObject("^" + attributePrefix);
			jsonGenerator.write("type", "string");
			jsonGenerator.writeEnd();
			jsonGenerator.writeEnd();
		}
		jsonGenerator.writeStartObject("properties");
		for (XSAttributeUse attributeUse : xsomHelper.getComplexType().getAttributeUses()) {
			XSAttributeDecl decl = attributeUse.getDecl();
			String attr = attributePrefix + decl.getName();
			jsonGenerator.writeStartObject(attr);
			generateType(decl.getType(), jsonGenerator);
			jsonGenerator.writeEnd();
			if (attributeUse.isRequired()) {
				required.add(attr);
			}
		}
		XSSimpleType simpleType = xsomHelper.getSimpleType();
		if (simpleType != null) {
			jsonGenerator.writeStartObject(valueWrapper);
			jsonGenerator.write("type", XSOMHelper.getJsonType(simpleType));
			jsonGenerator.writeEnd();
			required.add(valueWrapper);
		} else {
			for (int level = xsomHelper.getLevel();;) {
				XSTerm term = xsomHelper.nextElement();
				if (term == null || level >= xsomHelper.getLevel()) {
					if (term != null) {
						xsomHelper.push(term);
					}
					break;
				}
				if (xsomHelper.isLastElementAny()) {
					any = true;
					xsomHelper.endAny();
				} else {
					String name = term.apply(XSOMHelper.GetName);
					if (_nsMap != null) {
						String prefix = _nsMap.get(term.apply(XSOMHelper.GetNamespace));
						if (prefix != null && prefix.length() > 0) {
							name = prefix + '.' + name;
						}
					}
					jsonGenerator.writeStartObject(name);
					if (xsomHelper.isLastElementRequired()) {
						required.add(name);
					}
					generateType(xsomHelper, jsonGenerator);
					jsonGenerator.writeEnd(); // name
				}
			}
		}
		if (mixed) {
			jsonGenerator.writeStartObject("value");
			jsonGenerator.write("type", "array");
			jsonGenerator.writeStartObject("items");
			jsonGenerator.write("type", "string");
			jsonGenerator.writeEnd();
			jsonGenerator.writeEnd();
		}
		jsonGenerator.writeEnd(); // properties
		if (required.size() > 0) {
			jsonGenerator.writeStartArray("required");
			for (String string : required) {
				jsonGenerator.write(string);
			}
			jsonGenerator.writeEnd();
		}
		jsonGenerator.write("additionalProperties", any);
	}

	private void generateType(XSOMHelper xsomHelper, JsonGenerator jsonGenerator) throws SAXException {
		// decide object, array or primitive
		if (xsomHelper.isLastElementRepeated()) {
			jsonGenerator.write("type", "array");
			jsonGenerator.writeStartObject("items");
			if (xsomHelper.getComplexType() != null) {
				generateObject(xsomHelper, jsonGenerator);
			} else {
				generateType(xsomHelper.getSimpleType(), jsonGenerator);
			}
			jsonGenerator.writeEnd(); // items
		} else {
			if (xsomHelper.getComplexType() != null) {
				generateObject(xsomHelper, jsonGenerator);
			} else {
				XSSimpleType simpleType = xsomHelper.getSimpleType();
				if (simpleType != null) {
					generateType(simpleType, jsonGenerator);
				}
			}
		}
	}

	private void generateType(XSSimpleType simpleType, JsonGenerator jsonGenerator) {
		String jsonType = XSOMHelper.getJsonType(simpleType);
		String format = null;
		switch (jsonType) {
		case "int":
			jsonType = "integer";
			format = "int32";
			break;
		case "long":
			jsonType = "integer";
			format = "int64";
			break;
		case "date":
			jsonType = "string";
			format = "date";
			break;
		case "dateTime":
			jsonType = "string";
			format = "date-time";
			break;
		case "base64Binary":
			jsonType = "string";
			format = "byte";
			break;
		case "decimal":
			jsonType = "number";
			break;
		case "float":
			jsonType = "number";
			format = "float";
			break;
		case "double":
			jsonType = "number";
			format = "double";
			break;
		case "string":
		case "integer":
		case "boolean":
			break;
		default:
			throw new IllegalArgumentException("No mapping defined for " + jsonType);
		}
		jsonGenerator.write("type", jsonType);
		if (format != null) {
			jsonGenerator.write("format", format);
		}
		for (XSFacet facet : simpleType.getFacets(XSFacet.FACET_PATTERN)) {
			jsonGenerator.write("pattern", "^" + facet.getValue() + "$");
		}
		List<XSFacet> facets = simpleType.getFacets(XSFacet.FACET_ENUMERATION);
		if (facets.size() > 0) {
			jsonGenerator.writeStartArray("enum");
			for (XSFacet facet : facets) {
				jsonGenerator.write(facet.getValue().value);
			}
			jsonGenerator.writeEnd();
		}
	}

	public static String generate(String xsdFilename, String type) throws SAXException {
		XSOMParser xsomParser = new XSOMParser(JAXPFactoryHelper.getSAXParserFactory());
		xsomParser.parse(xsdFilename);
//		System.out.println("Number of parsed docs: " + xsomParser.getDocuments().size());
		JsonSchemaGenerator jsonSchemaGenerator = new JsonSchemaGenerator(xsomParser.getResult(), null);
		StringWriter stringWriter = new StringWriter();
		try (JsonGenerator jsonGenerator = JsonFactoryHelper.JSON_GENERATOR_FACTORY.createGenerator(stringWriter)) {
			jsonSchemaGenerator.generate(type, jsonGenerator);
		}
		return stringWriter.toString();
	}

}
