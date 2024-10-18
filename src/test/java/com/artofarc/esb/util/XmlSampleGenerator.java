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
package com.artofarc.esb.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import javax.xml.namespace.QName;
import javax.xml.transform.Transformer;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.DefaultHandler;

import com.artofarc.util.WSDL4JUtil;
import com.artofarc.util.XMLParserBase;
import com.artofarc.util.XMLProcessorFactory;
import com.artofarc.util.XSOMHelper;
import com.sun.xml.xsom.*;
import com.sun.xml.xsom.parser.XSOMParser;

public final class XmlSampleGenerator extends XMLParserBase {

	private final ArrayDeque<QName> stack = new ArrayDeque<>();
	private final QName _rootElement;
	private final XSComplexType _complexType;
	private LexicalHandler lexicalHandler;

	public XmlSampleGenerator(XSSchemaSet schemaSet, String rootElement, String typeName) {
		super(true, null);
		_rootElement = QName.valueOf(rootElement);
		XSElementDecl element = schemaSet.getElementDecl(_rootElement.getNamespaceURI(), _rootElement.getLocalPart());
		if (element != null) {
			_complexType = element.getType().asComplexType();
		} else {
			QName type = QName.valueOf(typeName);
			_complexType = schemaSet.getComplexType(type.getNamespaceURI(), type.getLocalPart());
		}
	}

	@Override
	public void setProperty(String name, Object value) throws SAXNotRecognizedException {
		if ("http://xml.org/sax/properties/lexical-handler".equals(name)) {
			lexicalHandler = (LexicalHandler) value;
		} else {
			throw new SAXNotRecognizedException("Property: " + name);
		}
	}
	
	private void comment(String s) throws SAXException {
		if (lexicalHandler != null) {
			lexicalHandler.comment(s.toCharArray(), 0, s.length());
		}
	}

	@Override
	public void parse(InputSource inputSource) throws SAXException {
		final XSOMHelper xsomHelper = new XSOMHelper(_complexType, null);
		final AttributesImpl atts = new AttributesImpl();
		startDocument();
		startElement(_rootElement.getNamespaceURI(), _rootElement.getLocalPart(), _rootElement.getLocalPart(), atts);
		stack.push(_rootElement);
		for (;;) {
			final XSTerm term = xsomHelper.nextElement();
			int level = xsomHelper.getLevel();
			while (level < stack.size()) {
				QName element = stack.pop();
				endElement(element.getNamespaceURI(), element.getLocalPart(), element.getLocalPart());
			}
			if (term == null) break;
			if (!xsomHelper.isLastElementRequired()) {
				comment("Optional:");
			}
			if (xsomHelper.isLastElementAny()) {
				comment("You may enter ANY element at this point");
				xsomHelper.endAny();
			} else {
				if (xsomHelper.isLastElementRepeated()) {
					comment("Repeated:");
				}
				QName element = new QName(term.apply(XSOMHelper.GetNamespace), term.apply(XSOMHelper.GetName));
				XSComplexType complexType = xsomHelper.getComplexType();
				if (complexType != null) {
					for (XSAttributeUse attributeUse : complexType.getAttributeUses()) {
						XSAttributeDecl decl = attributeUse.getDecl();
						XSSimpleType itemType = XSOMHelper.getItemType(decl.getType());
						XSSimpleType simpleType = itemType != null ? itemType : decl.getType();
						String value = attributeUse.getDefaultValue() != null ? attributeUse.getDefaultValue().value
								: attributeUse.getFixedValue() != null ? attributeUse.getFixedValue().value
										: getSampleValue(simpleType, simpleType.getFacets(XSFacet.FACET_ENUMERATION));
						atts.addAttribute(decl.getTargetNamespace(), decl.getName(), decl.getName(), "CDATA", value);
					}
				}
				XSSimpleType simpleType = xsomHelper.getSimpleType();
				if (simpleType != null) {
					XSSimpleType itemType = XSOMHelper.getItemType(simpleType);
					if (itemType != null) {
						comment("Simple list:");
						simpleType = itemType;
					}
					for (XSFacet facet : simpleType.getFacets(XSFacet.FACET_PATTERN)) {
						comment("Pattern: " + facet.getValue());
					}
					List<XSFacet> facets = simpleType.getFacets(XSFacet.FACET_ENUMERATION);
					if (facets.size() > 0) {
						ArrayList<String> values = new ArrayList<>();
						for (XSFacet facet : facets) {
							values.add(facet.getValue().value);
						}
						comment("Enumeration: " + values);
					}
					startElement(element.getNamespaceURI(), element.getLocalPart(), element.getLocalPart(), atts);
					XSElementDecl elementDecl = term.asElementDecl();
					if (elementDecl.getDefaultValue() != null ) {
						characters(elementDecl.getDefaultValue().value);
					} else if (elementDecl.getFixedValue() != null) {
						characters(elementDecl.getFixedValue().value);
					} else {
						characters(getSampleValue(simpleType, facets));
					}
					endElement(element.getNamespaceURI(), element.getLocalPart(), element.getLocalPart());
				} else {
					startElement(element.getNamespaceURI(), element.getLocalPart(), element.getLocalPart(), atts);
					stack.push(element);
				}
				atts.clear();
			}
		}
		endDocument();
	}

	private static String getSampleValue(XSSimpleType simpleType, List<XSFacet> facets) {
		switch (XSOMHelper.getJsonType(simpleType)) {
		case "int":
			return "42";
		case "long":
			return "4711";
		case "integer":
			return "1234567890";
		case "decimal":
			return "3.14";
		case "float":
		case "double":
			return "1E2";
		case "boolean":
			return "false";
		case "dateTime":
			return "2020-08-05T07:54:45.435Z";
		case "date":
			return "2020-08-05";
		case "time":
			return "07:54:45Z";
		case "base64Binary":
			return "YmluYXJ5";
		default:
			return facets.isEmpty() ? "string" : facets.get(0).getValue().value;
		}
	}

	public static XmlSampleGenerator createXmlSampleGenerator(String systemId, String rootElement, String typeName) throws Exception {
		XSOMParser xsomParser = new XSOMParser(XMLProcessorFactory.getSAXParserFactory());
		xsomParser.setErrorHandler(new DefaultHandler() {

			@Override
			public void error(SAXParseException e) throws SAXException {
				throw e;
			}
		});
		if (systemId.endsWith(".wsdl")) {
			Transformer transformer = XMLProcessorFactory.newTransformer();
			WSDL4JUtil.processSchemas(WSDL4JUtil.createWSDLReader(false).readWSDL(systemId), schemaElement -> {
				transformer.transform(new DOMSource(schemaElement, systemId), new SAXResult(xsomParser.getParserHandler()));
				transformer.reset();
			});
		} else {
			xsomParser.parse(systemId);
		}
		return new XmlSampleGenerator(xsomParser.getResult(), rootElement, typeName);
	}

}
