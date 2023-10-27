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
package com.artofarc.esb.util;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.json.stream.JsonGenerator;
import javax.xml.XMLConstants;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.artofarc.util.XMLProcessorFactory;
import com.artofarc.util.JsonFactoryHelper;
import com.artofarc.util.NamespaceMap;
import com.artofarc.util.StringBuilderWriter;
import com.artofarc.util.XSOMHelper;
import com.sun.xml.xsom.*;
import com.sun.xml.xsom.parser.XSOMParser;
import com.sun.xml.xsom.util.DomAnnotationParserFactory;

public final class JsonSchemaGenerator {

	private final XSSchemaSet _schemaSet;
	private final NamespaceMap _namespaceMap;
	private final String attributePrefix = "@";
	private final String valueWrapper = "value";

	public JsonSchemaGenerator(XSSchemaSet schemaSet, Map<String, String> prefixMap) {
		_schemaSet = schemaSet;
		_namespaceMap = prefixMap != null ? new NamespaceMap(prefixMap) : null;
	}

	public String generate(String scd) throws SAXException {
		StringBuilderWriter writer = new StringBuilderWriter();
		try (JsonGenerator jsonGenerator = JsonFactoryHelper.JSON_GENERATOR_FACTORY.createGenerator(writer)) {
			generate(scd, jsonGenerator);
		}
		return writer.toString();
	}

	public void generate(String scd, JsonGenerator jsonGenerator) throws SAXException {
		XSComponent component = _schemaSet.selectSingle(scd, _namespaceMap);
		if (component instanceof XSComplexType) {
			generate(new XSOMHelper((XSComplexType) component, null), jsonGenerator);
		} else if (component instanceof XSElementDecl) {
			generate(new XSOMHelper((XSElementDecl) component), jsonGenerator);
		} else if (component instanceof XSSimpleType) {
			jsonGenerator.writeStartObject();
			generateType((XSSimpleType) component, null, false, jsonGenerator);
			jsonGenerator.writeEnd();
		} else {
			throw new IllegalArgumentException(scd + " does not resolve to type or element, but " + component);
		}
	}

	private void generate(XSOMHelper xsomHelper, JsonGenerator jsonGenerator) throws SAXException {
		jsonGenerator.writeStartObject();
		// jsonGenerator.write("$schema", "http://json-schema.org/draft-04/schema#");
		generateObject(xsomHelper, jsonGenerator);
		jsonGenerator.writeEnd();
	}

	private void generateObject(XSOMHelper xsomHelper, JsonGenerator jsonGenerator) throws SAXException {
		List<String> required = new ArrayList<>();
		boolean any = false, mixed = xsomHelper.getComplexType().isMixed();
		generateDescription(xsomHelper.getComplexType(), jsonGenerator);
		if (xsomHelper.getWrappedElement() != null) {
			XSTerm term = xsomHelper.nextElement();
			generateType(xsomHelper, term.asElementDecl().getDefaultValue(), term.asElementDecl().isNillable(), jsonGenerator);
		} else {
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
				generateType(decl.getType(), decl.getDefaultValue(), false, jsonGenerator);
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
						generateType(xsomHelper, term.asElementDecl().getDefaultValue(), term.asElementDecl().isNillable(), jsonGenerator);
						generateDescription(term, jsonGenerator);
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
	}

	private void generateType(XSOMHelper xsomHelper, XmlString defaultValue, boolean nillable, JsonGenerator jsonGenerator) throws SAXException {
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
				generateType(xsomHelper.getSimpleType(), null, nillable, jsonGenerator);
			}
			jsonGenerator.writeEnd(); // items
		} else {
			if (xsomHelper.getComplexType() != null) {
				generateObject(xsomHelper, jsonGenerator);
			} else {
				XSSimpleType simpleType = xsomHelper.getSimpleType();
				if (simpleType != null) {
					generateType(simpleType, defaultValue, nillable, jsonGenerator);
				}
			}
		}
	}

	private static void generateType(XSSimpleType simpleType, XmlString defaultValue, boolean nillable, JsonGenerator jsonGenerator) {
		XSSimpleType itemType = XSOMHelper.getItemType(simpleType);
		if (itemType != null) {
			jsonGenerator.write("type", "array");
			jsonGenerator.writeStartObject("items");
			generateType(itemType, defaultValue, nillable, jsonGenerator);
			jsonGenerator.writeEnd();
		} else if (simpleType.isUnion()) {
			jsonGenerator.writeStartArray("anyOf");
			XSUnionSimpleType unionType = simpleType.asUnion();
			for (int i = 0; i < unionType.getMemberSize(); ++i) {
				jsonGenerator.writeStartObject();
				generateType(unionType.getMember(i), defaultValue, nillable, jsonGenerator);
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
			case "dateTime":
				jsonType = "string";
				format = "date-time";
				break;
			case "date":
				// Not draft 5
			case "time":
			case "duration":
				format = jsonType;
				jsonType = "string";
				break;
			case "base64Binary":
				jsonType = "string";
				format = "byte";
				break;
			case "anyURI":
				jsonType = "string";
				format = "uri";
				break;
			case "float":
			case "double":
				format = jsonType;
				// nobreak
			case "decimal":
				jsonType = "number";
				break;
			case "string":
			case "integer":
			case "boolean":
				break;
			default:
				jsonType = "string";
				break;
			}
			if (nillable) {
				jsonGenerator.writeStartArray("type").write(jsonType).write("null").writeEnd();
			} else {
				jsonGenerator.write("type", jsonType);
			}
			generateDescription(simpleType, jsonGenerator);
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

	private static void generateDescription(XSComponent component, JsonGenerator jsonGenerator) {
		XSAnnotation annotation = component.getAnnotation();
		if (annotation != null && annotation.getAnnotation() instanceof Element) {
			Element annotationElement = (Element) annotation.getAnnotation();
			NodeList childNodes = annotationElement.getChildNodes();
			for (int i = 0; i < childNodes.getLength(); ++i) {
				Node node = childNodes.item(i);
				if (node.getNodeType() == Node.ELEMENT_NODE && "documentation".equals(node.getLocalName()) && XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(node.getNamespaceURI())) {
					jsonGenerator.write("description", node.getTextContent().trim());
					break;
				}
			}
		}
	}

	public static JsonSchemaGenerator createJsonSchemaGenerator(String systemId, String namespace) throws SAXException {
		XSOMParser xsomParser = new XSOMParser(XMLProcessorFactory.getSAXParserFactory());
		xsomParser.setAnnotationParser(new DomAnnotationParserFactory());
		xsomParser.setErrorHandler(new ErrorHandler() {

			@Override
			public void warning(SAXParseException exception) throws SAXException {
				throw exception;
			}

			@Override
			public void fatalError(SAXParseException exception) throws SAXException {
				throw exception;
			}

			@Override
			public void error(SAXParseException exception) throws SAXException {
				throw exception;
			}
		});
		xsomParser.parse(systemId);
//		System.out.println("Number of parsed docs: " + xsomParser.getDocuments().size());
		XSSchemaSet result = xsomParser.getResult();
		if (result.getSchema(namespace) == null) {
			throw new IllegalArgumentException(namespace + " not found in " + systemId);
		}
		return new JsonSchemaGenerator(result, Collections.singletonMap("", namespace));
	}

	public static void main(String[] args) throws SAXException {
		System.out.println(createJsonSchemaGenerator(args[0], args[1]).generate(args[2]));
	}

}
