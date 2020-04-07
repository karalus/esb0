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
package com.artofarc.util;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Iterator;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;

import org.xml.sax.SAXException;

import com.sun.xml.xsom.*;
import com.sun.xml.xsom.parser.XSOMParser;
import com.sun.xml.xsom.visitor.XSTermFunction;

public final class XSOMHelper {

	static final class Group {

		private Group(XSComplexType owner, XSParticle particle, XSModelGroup modelGroup) {
			this.owner = owner;
			this.required = particle.getMinOccurs().signum() != 0;
			this.repeated = particle.isRepeated();
			this.modelGroup = modelGroup;
		}

		final XSComplexType owner;
		final boolean required;
		final boolean repeated;
		final XSModelGroup modelGroup;
		int pos;
		boolean startArray, middleArray, endArray;

		boolean hasNext() {
			return pos < modelGroup.getSize();
		}

		void next() {
			++pos;
		}

		XSParticle current() {
			return modelGroup.getChild(pos);
		}

		void nextChild() {
			if (!endArray && (current().isRepeated() && current().getTerm().isElementDecl() || repeated)) {
				startArray();
			} else {
				if (modelGroup.getCompositor() == XSModelGroup.CHOICE) {
					pos = modelGroup.getSize();
				} else {
					if (++pos == modelGroup.getSize() && modelGroup.getCompositor() == XSModelGroup.ALL) {
						pos = 0;
					}
				}
			}
		}

		void startArray() {
			if (startArray) {
				startArray = false;
				middleArray = true;
			} else if (!middleArray) {
				startArray = true;
			}
		}

		boolean endArray() {
			if (endArray) {
				endArray = false;
			} else if (startArray || middleArray) {
				startArray = false;
				middleArray = false;
				endArray = true;
			} else {
				next();
			}
			return middleArray || endArray;
		}

		@Override
		public String toString() {
			StringBuilder builder = new StringBuilder("Owner ComplexType: ");
			builder.append(owner.getName()).append('\n');
			builder.append("Modelgroup: ").append(modelGroup.getCompositor()).append('\n');
			for (int i=0; i <modelGroup.getSize(); ++i) {
				XSParticle particle = modelGroup.getChild(i);
				XSTerm term = particle.getTerm();
				if (term.isElementDecl()) {
					builder.append('\t').append("Element: ").append(term.asElementDecl().getName()).append('\n');
				} else {
					builder.append('\t').append("Modelgroup: ").append(term.asModelGroup().getCompositor()).append('\n');
				}
			}
			return builder.toString();
		}

	}
	
	public static final XSTermFunction<String> GetNamespace = new XSTermFunction<String>() {

		@Override
		public String elementDecl(XSElementDecl decl) {
			return decl.getTargetNamespace();
		}

		@Override
		public String wildcard(XSWildcard wc) {
			if (wc.acceptsNamespace(XMLConstants.NULL_NS_URI)) {
				return XMLConstants.NULL_NS_URI;
			} else if (wc.acceptsNamespace(wc.getOwnerSchema().getTargetNamespace())) {
				return wc.getOwnerSchema().getTargetNamespace();
			}
			throw new IllegalArgumentException("Could not guess namespace");
		}

		@Override
		public String modelGroupDecl(XSModelGroupDecl decl) {
			throw new IllegalArgumentException();
		}

		@Override
		public String modelGroup(XSModelGroup group) {
			throw new IllegalArgumentException();
		}
	};

	public static final XSTermFunction<String> GetName = new XSTermFunction<String>() {

		@Override
		public String elementDecl(XSElementDecl decl) {
			return decl.getName();
		}

		@Override
		public String wildcard(XSWildcard wc) {
			throw new IllegalArgumentException();
		}

		@Override
		public String modelGroupDecl(XSModelGroupDecl decl) {
			throw new IllegalArgumentException();
		}

		@Override
		public String modelGroup(XSModelGroup group) {
			throw new IllegalArgumentException();
		}
	};

	public static final XSSchemaSet anySchema;

	static {
		XSOMParser xsomParser = new XSOMParser(JAXPFactoryHelper.getSAXParserFactory());
		try {
			xsomParser.parse(new StringReader("<schema xmlns='http://www.w3.org/2001/XMLSchema'><element name='root' type='anyType'/></schema>"));
			anySchema = xsomParser.getResult();
		} catch (SAXException e) {
			throw new RuntimeException(e);
		}
	}

	private final ArrayDeque<ArrayDeque<Group>> stack = new ArrayDeque<>();

	private XSComplexType _complexType;
	private Group _currentGroup, _nextGroup;
	private XSSimpleType _simpleType;
	private boolean _any, _required;

	public XSOMHelper(XSComplexType complexType) {
		expandGroup(_complexType = complexType);
	}

	public int getLevel() {
		return stack.size();
	}

	private void saveCurrent() {
		if (_currentGroup != null && _currentGroup.hasNext()) {
			ArrayDeque<Group> context = stack.peek();
			context.push(_currentGroup);
		}
	}

	private void expandGroup(XSComplexType complexType) {
		final XSContentType contentType = complexType.getContentType();
		final XSParticle particle = contentType.asParticle();
		if (particle != null) {
			_nextGroup = new Group(complexType, particle, particle.getTerm().asModelGroup());
		} else {
			_simpleType = contentType.asSimpleType();
		}
	}

	public XSAttributeUse getAttributeUse(String nsURI, String localName) {
		if (nsURI != null) {
			return _complexType.getAttributeUse(nsURI, localName);
		}
		for (XSAttributeUse attributeUse : _complexType.getAttributeUses()) {
			if (attributeUse.getDecl().getName().equals(localName)) {
				return attributeUse;
			}
		}
		return null;
	}

	public XSWildcard getAttributeWildcard() {
		return _complexType.getAttributeWildcard();
	}

	public XSTerm matchElement(String uri, String localName) throws SAXException {
		if (_nextGroup != null) {
			saveCurrent();
			stack.push(new ArrayDeque<Group>());
			_currentGroup = _nextGroup;
			_nextGroup = null;
		}
		while (_currentGroup != null) {
			if (_currentGroup.hasNext()) {
				final XSParticle child = _currentGroup.current();
				_required = child.getMinOccurs().signum() != 0;
				final XSTerm term = child.getTerm();
				if (term.isElementDecl()) {
					final XSElementDecl element = term.asElementDecl();
					if ((localName == null || element.getName().equals(localName)) && (uri == null || element.getTargetNamespace().equals(uri))) {
						nextParticle();
						processElement(element);
						return element;
					}
					if (_required && _currentGroup.modelGroup.getCompositor() == XSModelGroup.SEQUENCE) {
						throw new SAXException("Missing required element: " + new QName(element.getTargetNamespace(), element.getName()));
					}
					if (endArray()) {
						_currentGroup.next();
					}
				} else if (term.isModelGroup()) {
					nextParticle();
					saveCurrent();
					_currentGroup = new Group(getCurrentComplexType(), child, term.asModelGroup());
				} else if (term.isModelGroupDecl()) {
					nextParticle();
					saveCurrent();
					_currentGroup = new Group(getCurrentComplexType(), child, term.asModelGroupDecl().getModelGroup());
				} else if (term.isWildcard()) {
					if (child.isRepeated()) {
						_currentGroup.startArray();
					}
					final XSWildcard wildcard = term.asWildcard();
					if (wildcard.getMode() != XSWildcard.SKIP) {
						for (Iterator<XSElementDecl> iterator = wildcard.getRoot().iterateElementDecls(); iterator.hasNext();) {
							XSElementDecl element = iterator.next();
							if (element.getName().equals(localName) && (uri == null || element.getTargetNamespace().equals(uri))
									&& wildcard.acceptsNamespace(element.getTargetNamespace())) {
								if (!(_currentGroup.startArray || _currentGroup.middleArray)) {
									_currentGroup.next();
								}
								_currentGroup.nextChild();
								processElement(element);
								return element;
							}
						}
						if (wildcard.getMode() == XSWildcard.STRTICT) {
							throw new SAXException("Did not find any element " + new QName(uri, localName));
						}
					}
					_simpleType = null;
					_any = true;
					return wildcard;
				}
			} else {
				nextParticle();
			}
		}
		if (localName != null) {
			throw new SAXException("Element not expected: " + new QName(uri, localName));
		}
		return null;
	}

	private void nextParticle() {
		if (_currentGroup != null && _currentGroup.hasNext()) {
			_currentGroup.nextChild();
		} else {
			for (ArrayDeque<Group> context = stack.peek(); context != null;) {
				_currentGroup = context.peek();
				if (_currentGroup == null) {
					stack.pop();
					context = stack.peek();
				} else {
					if (_currentGroup.hasNext()) {
						break;
					} else {
						context.pop();
					}
				}
			}
		}
	}

	private void processElement(XSElementDecl element) {
		final XSType type = element.getType();
		_complexType = type.asComplexType();
		if (type.isComplexType()) {
			_simpleType = null;
			expandGroup(_complexType);
		} else {
			_simpleType = type.asSimpleType();
		}
		_any = false;
	}

	public boolean isLastElementComplex() {
		return _complexType != null;
	}

	public XSComplexType getCurrentComplexType() {
		return _currentGroup.owner;
	}

	public XSSimpleType getSimpleType() {
		return _simpleType;
	}

	public boolean isLastElementAny() {
		return _any;
	}

	public boolean isLastElementRequired() {
		return _required;
	}

	public boolean isStartArray() {
		return _currentGroup != null && _currentGroup.startArray;
	}

	public boolean isMiddleOfArray() {
		return _currentGroup != null && _currentGroup.middleArray;
	}

	public boolean isInArray() {
		return _currentGroup != null && (_currentGroup.startArray || _currentGroup.middleArray);
	}

	public boolean isEndArray() {
		return _currentGroup != null && _currentGroup.endArray;
	}

	public boolean endArray() {
		return _currentGroup.endArray();
	}

	public void endComplex() {
		// TODO: pop should work
		stack.poll();
		final ArrayDeque<Group> context = stack.peek();
		if (context != null) {
			_currentGroup = context.poll();
		}
	}

	public void endAny() {
		nextParticle();
	}

	public static String getJsonType(XSSimpleType simpleType) {
		while (!simpleType.isPrimitive()) {
			if (simpleType.getTargetNamespace().equals(XMLConstants.W3C_XML_SCHEMA_NS_URI)
					&& (simpleType.getName().equals("int") || simpleType.getName().equals("long") || simpleType.getName().equals("integer"))) {
				break;
			}
			simpleType = simpleType.getBaseType().asSimpleType();
		}
		return simpleType.getName();
	}

}
