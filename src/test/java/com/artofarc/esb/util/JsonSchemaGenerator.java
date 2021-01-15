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
package com.artofarc.esb.util;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.json.stream.JsonGenerator;

import org.xml.sax.SAXException;

import com.artofarc.util.JAXPFactoryHelper;
import com.artofarc.util.JsonFactoryHelper;
import com.artofarc.util.NamespaceMap;
import com.artofarc.util.XSOMHelper;
import com.sun.xml.xsom.*;
import com.sun.xml.xsom.parser.XSOMParser;

public final class JsonSchemaGenerator {

	private final XSSchemaSet _schemaSet;
	private final NamespaceMap _namespaceMap;
	private final String attributePrefix = "@";
	private final String valueWrapper = "value";

	public JsonSchemaGenerator(XSSchemaSet schemaSet, Map<String, String> prefixMap) {
		_schemaSet = schemaSet;
		_namespaceMap = prefixMap != null ? new NamespaceMap(prefixMap) : null;
	}

	public String generate(String scd) {
		StringWriter stringWriter = new StringWriter();
		try (JsonGenerator jsonGenerator = JsonFactoryHelper.JSON_GENERATOR_FACTORY.createGenerator(stringWriter)) {
			generate(scd, jsonGenerator);
		}
		return stringWriter.toString();
	}

	public void generate(String scd, JsonGenerator jsonGenerator) {
		XSComponent component = _schemaSet.selectSingle(scd, _namespaceMap);
		if (component instanceof XSComplexType) {
			generate(new XSOMHelper((XSComplexType) component, null), jsonGenerator);
		} else if (component instanceof XSElementDecl) {
			generate(new XSOMHelper((XSElementDecl) component), jsonGenerator);
		} else if (component instanceof XSSimpleType) {
			jsonGenerator.writeStartObject();
			generateType((XSSimpleType) component, null, jsonGenerator);
			jsonGenerator.writeEnd();
		} else {
			throw new IllegalArgumentException(scd + " does not resolve to complex type or element, but " + component);
		}
	}

	private void generate(XSOMHelper xsomHelper, JsonGenerator jsonGenerator) {
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
			generateType(decl.getType(), decl.getDefaultValue(), jsonGenerator);
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
					if (_namespaceMap != null) {
						String prefix = _namespaceMap.getPrefix(term.apply(XSOMHelper.GetNamespace));
						if (prefix != null && prefix.length() > 0) {
							name = prefix + '.' + name;
						}
					}
					jsonGenerator.writeStartObject(name);
					if (xsomHelper.isLastElementRequired()) {
						required.add(name);
					}
					generateType(xsomHelper, term.asElementDecl().getDefaultValue(), jsonGenerator);
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

	private void generateType(XSOMHelper xsomHelper, XmlString defaultValue, JsonGenerator jsonGenerator) throws SAXException {
		// decide object, array or primitive
		if (xsomHelper.isLastElementRepeated()) {
			jsonGenerator.write("type", "array");
			if (xsomHelper.isLastElementRequired()) {
				jsonGenerator.write("minItems", 1);
			}
			jsonGenerator.writeStartObject("items");
			if (xsomHelper.getComplexType() != null) {
				generateObject(xsomHelper, jsonGenerator);
			} else {
				generateType(xsomHelper.getSimpleType(), null, jsonGenerator);
			}
			jsonGenerator.writeEnd(); // items
		} else {
			if (xsomHelper.getComplexType() != null) {
				generateObject(xsomHelper, jsonGenerator);
			} else {
				XSSimpleType simpleType = xsomHelper.getSimpleType();
				if (simpleType != null) {
					generateType(simpleType, defaultValue, jsonGenerator);
				}
			}
		}
	}

	private void generateType(XSSimpleType simpleType, XmlString defaultValue, JsonGenerator jsonGenerator) {
		XSSimpleType itemType = XSOMHelper.getItemType(simpleType);
		if (itemType != null) {
			jsonGenerator.write("type", "array");
			jsonGenerator.writeStartObject("items");
			generateType(itemType, defaultValue, jsonGenerator);
			jsonGenerator.writeEnd();
		} else if (simpleType.isUnion()) {
			jsonGenerator.writeStartArray("anyOf");
			XSUnionSimpleType unionType = simpleType.asUnion();
			for (int i = 0; i < unionType.getMemberSize(); ++i) {
				jsonGenerator.writeStartObject();
				generateType(unionType.getMember(i), defaultValue, jsonGenerator);
				jsonGenerator.writeEnd();
			}
			jsonGenerator.writeEnd();
		} else {
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
			if (jsonType.equals("string")) {
				XSFacet facetMinlength = simpleType.getFacet(XSFacet.FACET_MINLENGTH);
				if (facetMinlength != null) {
					jsonGenerator.write("minLength", Integer.parseInt(facetMinlength.getValue().value));
				}
				XSFacet facetMaxlength = simpleType.getFacet(XSFacet.FACET_MAXLENGTH);
				if (facetMaxlength != null) {
					jsonGenerator.write("maxLength", Integer.parseInt(facetMaxlength.getValue().value));
				}
				for (XSFacet facet : simpleType.getFacets(XSFacet.FACET_PATTERN)) {
					jsonGenerator.write("pattern", "^" + facet.getValue() + "$");
				}
			} else if ((jsonType.equals("integer") || jsonType.equals("number")) && format == null) {
				// if a format is given then we omit the given minimum & maximum
				XSFacet facetMinexclusive = simpleType.getFacet(XSFacet.FACET_MINEXCLUSIVE);
				if (facetMinexclusive != null) {
					jsonGenerator.write("minimum", new BigDecimal(facetMinexclusive.getValue().value));
					jsonGenerator.write("exclusiveMinimum", true);
				} else {
					XSFacet facetMininclusive = simpleType.getFacet(XSFacet.FACET_MININCLUSIVE);
					if (facetMininclusive != null) {
						jsonGenerator.write("minimum", new BigDecimal(facetMininclusive.getValue().value));
					}
				}
				XSFacet facetMaxexclusive = simpleType.getFacet(XSFacet.FACET_MAXEXCLUSIVE);
				if (facetMaxexclusive != null) {
					jsonGenerator.write("maximum", new BigDecimal(facetMaxexclusive.getValue().value));
					jsonGenerator.write("exclusiveMaximum", true);
				} else {
					XSFacet facetMaxinclusive = simpleType.getFacet(XSFacet.FACET_MAXINCLUSIVE);
					if (facetMaxinclusive != null) {
						jsonGenerator.write("maximum", new BigDecimal(facetMaxinclusive.getValue().value));
					}
				}
			}
			List<XSFacet> facets = simpleType.getFacets(XSFacet.FACET_ENUMERATION);
			if (facets.size() > 0) {
				jsonGenerator.writeStartArray("enum");
				for (XSFacet facet : facets) {
					jsonGenerator.write(facet.getValue().value);
				}
				jsonGenerator.writeEnd();
			}
			if (defaultValue != null) {
				jsonGenerator.write("default", defaultValue.value);
			}
		}
	}

	public static JsonSchemaGenerator createJsonSchemaGenerator(String systemId, String namespace) throws SAXException {
		XSOMParser xsomParser = new XSOMParser(JAXPFactoryHelper.getSAXParserFactory());
		xsomParser.parse(systemId);
//		System.out.println("Number of parsed docs: " + xsomParser.getDocuments().size());
		Map<String, String> prefixMap = new HashMap<String, String>();
		prefixMap.put("", namespace);
		return new JsonSchemaGenerator(xsomParser.getResult(), prefixMap);
	}

}
