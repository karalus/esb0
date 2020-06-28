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
package com.artofarc.esb.jdbc;

import java.io.IOException;
import java.sql.Array;
import java.sql.Blob;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Struct;
import java.util.Date;

import javax.xml.XMLConstants;
import javax.xml.bind.DatatypeConverter;
import javax.xml.namespace.QName;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.sax.SAXSource;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.AttributesImpl;

import com.artofarc.esb.context.Context;
import com.artofarc.util.XMLParserBase;
import com.artofarc.util.XSOMHelper;
import com.sun.xml.xsom.XSElementDecl;
import com.sun.xml.xsom.XSSchemaSet;
import com.sun.xml.xsom.XSSimpleType;
import com.sun.xml.xsom.XSTerm;

public final class JDBC2XMLMapper {

	private final String _rootUri, _rootName;
	private final XSElementDecl _element;

	public JDBC2XMLMapper(XSSchemaSet schemaSet, QName rootElement) {
		_element = schemaSet.getElementDecl(_rootUri = rootElement.getNamespaceURI(), _rootName = rootElement.getLocalPart());
	}

	public String getTypeName() throws SAXException {
		XSOMHelper xsomHelper = new XSOMHelper(_element);
		if (xsomHelper.getComplexType().getName() == null) {
			xsomHelper.nextElement();
		}
		return xsomHelper.getComplexType().getName();
	}

	public XMLReader createParser(Context context, Struct struct) {
		return new Parser(context, struct);
	}

	// Not thread safe
	private final class Parser extends XMLParserBase {

		final Struct _struct;
		final Context _context;
		final XSOMHelper _xsomHelper = new XSOMHelper(_element);
		final AttributesImpl _atts = new AttributesImpl();

		Parser(Context context, Struct struct) {
			super(true, null);
			_struct = struct;
			_context = context;
		}

		@Override
		public void startDocument() {
			// ignore (from SAXSource);
		}

		@Override
		public void endDocument() {
			// ignore (from SAXSource);
		}

		@Override
		public void parse(InputSource inputSource) throws SAXException {
			super.startDocument();
			startElement(_rootUri, _rootName, _rootName, _atts);
			try {
				parse(_struct);
			} catch (SQLException | IOException | ParserConfigurationException e) {
				throw new SAXException(e);
			}
			endElement(_rootUri, _rootName, _rootName);
			super.endDocument();
		}

		private void parse(Struct struct) throws SAXException, SQLException, IOException, ParserConfigurationException {
			XSTerm term = _xsomHelper.nextElement();
			_xsomHelper.checkComplexType(struct.getSQLTypeName());
			if (_xsomHelper.isLastElementRepeated()) {
				_xsomHelper.repeatElement();
			}
			final String _uri = term.apply(XSOMHelper.GetNamespace);
			final String _name = term.apply(XSOMHelper.GetName);
			startElement(_uri, _name, _name, _atts);
			for (Object attribute : struct.getAttributes()) {
				if (attribute instanceof Struct) {
					Struct inner = (Struct) attribute;
					parse(inner);
				} else if (attribute instanceof Array) {
					Array inner = (Array) attribute;
					term = _xsomHelper.nextElement();
					_xsomHelper.checkComplexType(JDBCConnection.getSQLTypeName(inner));
					String uri = term.apply(XSOMHelper.GetNamespace);
					String name = term.apply(XSOMHelper.GetName);
					startElement(uri, name, name, _atts);
					Object[] array = (Object[]) inner.getArray();
					if (array.length > 0) {
						for (Object element : array) {
							if (element instanceof Struct) {
								parse((Struct) element);
							} else {
								writeValue(element);
							}
						}
						_xsomHelper.endArray();
					} else {
						_xsomHelper.nextElement();
					}
					endElement(uri, name, name);
					_xsomHelper.endComplex();
				} else if (attribute instanceof SQLXML) {
					term = _xsomHelper.nextElement();
					String uri = term.apply(XSOMHelper.GetNamespace);
					String name = term.apply(XSOMHelper.GetName);
					if (_xsomHelper.getComplexType() == null) {
						throw new SAXException("Expected complex wrapper type for any in " + name);
					}
					term = _xsomHelper.nextElement();
					if (!_xsomHelper.isLastElementAny()) {
						throw new SAXException("Expected any type for " + name);
					}
					startElement(uri, name, name, _atts);
					SQLXML sqlxml = (SQLXML) attribute;
					SAXSource saxSource = sqlxml.getSource(SAXSource.class);
					XMLReader xmlReader = saxSource.getXMLReader();
					if (xmlReader == null) {
						// Oracle does not deliver a XMLReader
						xmlReader = _context.getSAXParser().getXMLReader();
					}
					xmlReader.setContentHandler(this);
					xmlReader.parse(saxSource.getInputSource());
					sqlxml.free();
					endElement(uri, name, name);
					_xsomHelper.endAny();
				} else if (attribute != null) {
					writeValue(attribute);
				} else {
					term = _xsomHelper.nextElement();
					if (_xsomHelper.isLastElementAny()) {
						_xsomHelper.endAny();
					} else {
						if (_xsomHelper.isLastElementRequired() && term.asElementDecl().isNillable()) {
							String uri = term.apply(XSOMHelper.GetNamespace);
							String name = term.apply(XSOMHelper.GetName);
							startPrefixMapping("xsi", XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI);
							_atts.addAttribute(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "nil", "xsi:nil", "CDATA", "true");
							startElement(uri, name, name, _atts);
							endElement(uri, name, name);
							_atts.clear();
							endPrefixMapping("xsi");
						}
						if (_xsomHelper.getComplexType() != null) {
							_xsomHelper.endComplex();
						}
					}
				}
			}
			endElement(_uri, _name, _name);
			_xsomHelper.endComplex();
		}

		private void writeValue(Object attribute) throws SAXException, SQLException {
			final XSTerm term = _xsomHelper.nextElement();
			final XSSimpleType simpleType = _xsomHelper.getSimpleType();
			if (simpleType == null) {
				throw new SAXException("Expected simple type for " + attribute);
			}
			final String uri = term.apply(XSOMHelper.GetNamespace);
			final String name = term.apply(XSOMHelper.GetName);
			startElement(uri, name, name, _atts);
			String value;
			switch (XSOMHelper.getJsonType(simpleType)) {
			case "base64Binary":
				Blob blob = (Blob) attribute;
				value = DatatypeConverter.printBase64Binary(blob.getBytes(1, (int) blob.length()));
				blob.free();
				break;
			case "date":
				value = DatatypeConverter.printDate(JDBCParameter.convert((Date) attribute));
				break;
			case "dateTime":
				value = DatatypeConverter.printDateTime(JDBCParameter.convert((Date) attribute));
				break;
			default:
				value = attribute.toString();
				break;
			}
			characters(value);
			endElement(uri, name, name);
		}

	}

}
