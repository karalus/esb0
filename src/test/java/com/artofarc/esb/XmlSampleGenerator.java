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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import javax.xml.namespace.QName;

import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.AttributesImpl;

import com.artofarc.util.XMLFilterBase;
import com.artofarc.util.XSOMHelper;
import com.sun.xml.xsom.*;

public final class XmlSampleGenerator extends XMLFilterBase {

	private final ArrayDeque<QName> stack = new ArrayDeque<>();
	private final QName _rootElement;
	private final XSElementDecl _element;
	private LexicalHandler lexicalHandler;

	public XmlSampleGenerator(XSSchemaSet schemaSet, String rootElement) {
		_rootElement = QName.valueOf(rootElement);
		_element = schemaSet.getElementDecl(_rootElement.getNamespaceURI(), _rootElement.getLocalPart());
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
		final ContentHandler ch = getContentHandler();
		if (ch == null) {
			throw new SAXException("ContentHandler not set");
		}
		final XSOMHelper xsomHelper = new XSOMHelper(_element);
		final AttributesImpl atts = new AttributesImpl();
		ch.startDocument();
		ch.startElement(_rootElement.getNamespaceURI(), _rootElement.getLocalPart(), _rootElement.getLocalPart(), atts);
		stack.push(_rootElement);
		for (;;) {
			final XSTerm term = xsomHelper.nextElement();
			int level = xsomHelper.getLevel();
			while (level < stack.size()) {
				QName element = stack.pop();
				ch.endElement(element.getNamespaceURI(), element.getLocalPart(), element.getLocalPart());
			}
			if (term == null) break;
			if (!xsomHelper.isLastElementRequired()) {
				comment("Optional:");
			}
			if (xsomHelper.isLastElementAny()) {
				comment("You may enter ANY element at this point");
				xsomHelper.endAny();
			} else {
				if (xsomHelper.isStartArray()) {
					while (xsomHelper.endArray());
					xsomHelper.endArray();
					comment("Repeated:");
				}
				QName element = new QName(term.apply(XSOMHelper.GetNamespace), term.apply(XSOMHelper.GetName));
				XSComplexType complexType = xsomHelper.getComplexType();
				if (complexType != null) {
					for (XSAttributeUse attributeUse : complexType.getAttributeUses()) {
						XSAttributeDecl decl = attributeUse.getDecl();
						String value = attributeUse.getDefaultValue() != null ? attributeUse.getDefaultValue().value
								: getSampleValue(decl.getType(), decl.getType().getFacets(XSFacet.FACET_ENUMERATION));
						atts.addAttribute(decl.getTargetNamespace(), decl.getName(), decl.getName(), "CDATA", value);
					}
				}
				XSSimpleType simpleType = xsomHelper.getSimpleType();
				if (simpleType != null) {
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
					ch.startElement(element.getNamespaceURI(), element.getLocalPart(), element.getLocalPart(), atts);
					String s = getSampleValue(simpleType, facets);
					ch.characters(s.toCharArray(), 0, s.length());
					ch.endElement(element.getNamespaceURI(), element.getLocalPart(), element.getLocalPart());
				} else {
					ch.startElement(element.getNamespaceURI(), element.getLocalPart(), element.getLocalPart(), atts);
					stack.push(element);
				}
				atts.clear();
			}
		}
		ch.endDocument();
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
		default:
			return facets.isEmpty() ? "string" : facets.get(0).getValue().value;
		}

	}

}
