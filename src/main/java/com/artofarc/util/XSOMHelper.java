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
import java.util.Map.Entry;

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
			this.all = modelGroup.getCompositor() == XSModelGroup.ALL;
			this.choice = modelGroup.getCompositor() == XSModelGroup.CHOICE;
		}

		final XSComplexType owner;
		final boolean required;
		final boolean repeated;
		final XSModelGroup modelGroup;
		final boolean all, choice;
		int pos, count;
		boolean startArray, middleArray, endArray;

		boolean hasNext() {
			return pos < modelGroup.getSize() && (!all || count < modelGroup.getSize());
		}

		void next() {
			if (++pos == modelGroup.getSize() & (all && ++count < modelGroup.getSize())) {
				pos = 0;
			}
		}
		
		void found() {
			count = 0;
		}

		XSParticle current() {
			return modelGroup.getChild(pos);
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
			print(modelGroup, "", builder);
			return builder.toString();
		}

		private static void print(XSModelGroup modelGroup, String indent, StringBuilder builder) {
			builder.append(indent).append("Modelgroup: ").append(modelGroup.getCompositor()).append('\n');
			for (int i = 0; i < modelGroup.getSize(); ++i) {
				XSParticle particle = modelGroup.getChild(i);
				XSTerm term = particle.getTerm();
				if (term.isElementDecl()) {
					builder.append(indent).append('\t').append("Element: ").append(term.asElementDecl().getName()).append('\n');
				} else if (term.isModelGroup()) {
					print(term.asModelGroup(), indent + '\t', builder);
				} else if (term.isModelGroupDecl()) {
					builder.append(indent).append('\t').append("Group ref: ").append(term.asModelGroupDecl().getName());
					print(term.asModelGroupDecl().getModelGroup(), indent + '\t', builder);
				} else if (term.isWildcard()) {
					builder.append(indent).append('\t').append("Any").append('\n');
				}
			}
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
			}
			if (wc.acceptsNamespace(wc.getOwnerSchema().getTargetNamespace())) {
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

	private final ArrayDeque<Entry<String, ArrayDeque<Group>>> _stack = new ArrayDeque<>();
	private XSTerm _lastTerm;
	private XSComplexType _complexType;
	private Group _currentGroup, _nextGroup;
	private String _nextType;
	private XSSimpleType _simpleType;
	private boolean _any, _required, _repeated;

	public XSOMHelper(XSComplexType complexType, XSElementDecl elementDecl) {
		expandGroup(_complexType = complexType != null ? complexType : elementDecl.getType().asComplexType(), elementDecl);
	}

	public XSOMHelper(XSElementDecl elementDecl) {
		expandGroup(_complexType = elementDecl.getType().asComplexType(), elementDecl);
	}

	public int getLevel() {
		return _stack.size();
	}

	private void saveCurrent() {
		if (_currentGroup != null && _currentGroup.hasNext()) {
			Entry<String, ArrayDeque<Group>> context = _stack.peek();
			context.getValue().push(_currentGroup);
		}
	}

	private void expandGroup(XSComplexType complexType, XSElementDecl element) {
		final XSContentType contentType = complexType.getContentType();
		final XSParticle particle = contentType.asParticle();
		if (particle != null) {
			_nextType = complexType.getName();
			if (_nextType == null) {
				_nextType = "Anonymous type in " + (element != null ? element.getName() : "unknown element");
			}
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

	public void push(XSTerm term) {
		if (_lastTerm != null) {
			throw new IllegalStateException("can only pushback one term");
		}
		_lastTerm = term;
	}

	public XSTerm poll() {
		final XSTerm term = _lastTerm;
		_lastTerm = null;
		return term;
	}

	public XSTerm nextElement() throws SAXException {
		final XSTerm term = poll();
		return term != null ? term : matchElement(null, null);
	}

	public XSTerm matchElement(String uri, String localName) throws SAXException {
		if (_nextGroup != null) {
			saveCurrent();
			_stack.push(Collections.createEntry(_nextType, new ArrayDeque<>()));
			_currentGroup = _nextGroup;
			_nextGroup = null;
		}
		while (_currentGroup != null) {
			if (_currentGroup.hasNext()) {
				final XSParticle child = _currentGroup.current();
				_required = child.getMinOccurs().signum() != 0;
				_repeated = child.isRepeated();
				final XSTerm term = child.getTerm();
				if (term.isElementDecl()) {
					final XSElementDecl element = term.asElementDecl();
					if ((localName == null || element.getName().equals(localName)) && (uri == null || element.getTargetNamespace().equals(uri))) {
						if (localName != null) {
							foundParticle();
						} else {
							nextParticle();
						}
						processElement(element);
						return element;
					}
					if (_required && !_currentGroup.all) {
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
					if (_repeated) {
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
								_currentGroup.next();
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

	private void foundParticle() {
		_currentGroup.found();
		if (!_currentGroup.endArray && _repeated || _currentGroup.repeated) {
			_currentGroup.startArray();
		} else {
			nextParticle();
		}
		Entry<String, ArrayDeque<Group>> context = _stack.peek();
		if (context != null) {
			for (Group group : context.getValue()) {
				group.found();
			}
		}
	}

	private void nextParticle() {
		if (_currentGroup != null && _currentGroup.hasNext()) {
			_currentGroup.next();
		} else {
			for (Entry<String, ArrayDeque<Group>> context = _stack.peek(); context != null;) {
				_currentGroup = context.getValue().peek();
				if (_currentGroup == null) {
					context = _stack.pop();
					context = _stack.peek();
				} else {
					if (_currentGroup.hasNext()) {
						break;
					} else {
						context.getValue().pop();
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
			expandGroup(_complexType, element);
		} else {
			_simpleType = type.asSimpleType();
		}
		_any = false;
	}

	public XSComplexType getComplexType() {
		return _complexType;
	}

	public XSComplexType getCurrentComplexType() {
		return _currentGroup != null ? _currentGroup.owner : _complexType;
	}

	public XSSimpleType getSimpleType() {
		return _simpleType;
	}

	public boolean isListSimpleType() {
		if (_simpleType != null) {
			if (_simpleType.isList()) {
				return true;
			}
			if (_simpleType.isUnion()) {
				XSUnionSimpleType unionType = _simpleType.asUnion();
				for (int i = unionType.getMemberSize(); i > 0;) {
					if (unionType.getMember(--i).isList()) {
						return true;
					}
				}
			}
		}
		return false;
	}

	public XSListSimpleType getListSimpleTypeFromUnion(String xsdType) throws SAXException {
		if (_simpleType == null || !_simpleType.isUnion()) {
			throw new SAXException("Current type is not xs:union");
		}
		if (xsdType == null) {
			xsdType = "string";
		}
		XSUnionSimpleType unionType = _simpleType.asUnion();
		for (int i = unionType.getMemberSize(); i > 0;) {
			XSSimpleType simpleType = unionType.getMember(--i);
			if (simpleType.isList()) {
				XSListSimpleType listSimpleType = simpleType.asList();
				if (xsdType.equals(getJsonBaseType(listSimpleType.getItemType()))) {
					if (listSimpleType.getName() == null) {
						throw new SAXException("xs:union type must not contain anonymous xs:list type");
					}
					return listSimpleType;
				}
			}
		}
		throw new SAXException("xs:union type does not contain xs:list type for " + xsdType);
	}

	public boolean isLastElementAny() {
		return _any;
	}

	public boolean isLastElementRequired() {
		return _required && !_currentGroup.choice;
	}

	public boolean isLastElementRepeated() {
		return _repeated || (_currentGroup != null && _currentGroup.repeated);
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

	public void startArray() {
		--_currentGroup.pos;
	}

	public boolean endArray() {
		_nextGroup = null;
		return _currentGroup.endArray();
	}

	public void endComplex() {
		if (_nextGroup != null) {
			_nextGroup = null;
		} else {
			Entry<String, ArrayDeque<Group>> context = _stack.pop();
			context = _stack.peek();
			if (context != null) {
				_currentGroup = context.getValue().poll();
			}
		}
	}

	public void endAny() {
		if (_currentGroup != null && _currentGroup.hasNext()) {
			_currentGroup.next();
		}
		_complexType = null;
	}

	public void checkComplexType(String typeName) throws SAXException {
		if (_complexType == null) {
			throw new SAXException("Expected complex type for " + typeName);
		}
		if (!typeName.equals(_complexType.getName())) {
			throw new SAXException("Expected complex type " + typeName + ", but got " + _complexType.getName());
		}
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

	public static String getJsonBaseType(XSSimpleType simpleType) {
		while (!simpleType.isPrimitive()) {
			if (simpleType.getTargetNamespace().equals(XMLConstants.W3C_XML_SCHEMA_NS_URI) && simpleType.getName().equals("integer")) {
				break;
			}
			simpleType = simpleType.getBaseType().asSimpleType();
		}
		return simpleType.getName();
	}

}
