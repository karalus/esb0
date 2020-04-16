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

import javax.xml.bind.DatatypeConverter;
import javax.xml.transform.sax.SAXSource;

import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.AttributesImpl;

import com.artofarc.util.XMLFilterBase;
import com.artofarc.util.XSOMHelper;
import com.sun.xml.xsom.XSElementDecl;
import com.sun.xml.xsom.XSSchemaSet;
import com.sun.xml.xsom.XSSimpleType;
import com.sun.xml.xsom.XSTerm;

public final class JDBC2XMLMapper {

	private final String _rootUri, _rootName;
	private final XSElementDecl _element;

	public JDBC2XMLMapper(XSSchemaSet schemaSet, String rootUri, String rootName) {
		_element = schemaSet.getElementDecl(_rootUri = rootUri, _rootName = rootName);
	}

	public XMLReader createParser(Struct struct) {
		return new Parser(struct);
	}

	// Not thread safe
	private final class Parser extends XMLFilterBase {

		final Struct _struct;
		final XSOMHelper _xsomHelper = new XSOMHelper(_element);
		final AttributesImpl _atts = new AttributesImpl();

		Parser(Struct struct) {
			_struct = struct;
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
			final ContentHandler ch = getContentHandler();
			if (ch == null) {
				throw new SAXException("ContentHandler not set");
			}
			ch.startDocument();
			ch.startElement(_rootUri, _rootName, _rootName, _atts);
			try {
				parse(_struct);
			} catch (SQLException | IOException e) {
				throw new SAXException(e);
			}
			ch.endElement(_rootUri, _rootName, _rootName);
			ch.endDocument();
		}

		private void parse(Struct struct) throws SAXException, SQLException, IOException {
			XSTerm term = _xsomHelper.matchElement(null, null);
			_xsomHelper.checkComplexType(struct.getSQLTypeName());
			final ContentHandler ch = getContentHandler();
			final String _uri = term.apply(XSOMHelper.GetNamespace);
			final String _name = term.apply(XSOMHelper.GetName);
			ch.startElement(_uri, _name, _name, _atts);
			for (Object attribute : struct.getAttributes()) {
				if (attribute instanceof Struct) {
					Struct inner = (Struct) attribute;
					parse(inner);
				} else if (attribute instanceof Array) {
					Array inner = (Array) attribute;
					term = _xsomHelper.matchElement(null, null);
					_xsomHelper.checkComplexType(JDBCConnection.getSQLTypeName(inner));
					String uri = term.apply(XSOMHelper.GetNamespace);
					String name = term.apply(XSOMHelper.GetName);
					ch.startElement(uri, name, name, _atts);
					term = _xsomHelper.matchElement(null, null);
					if (!_xsomHelper.isStartArray()) {
						throw new SAXException("Expected array in " + _xsomHelper.getCurrentComplexType().getName());
					}
					for (Object element : (Object[]) inner.getArray()) {
						if (element instanceof Struct) {
							parse((Struct) element);
						} else {
							writeValue(element);
						}
					}
					ch.endElement(uri, name, name);
					_xsomHelper.endArray();
				} else if (attribute instanceof SQLXML) {
					term = _xsomHelper.matchElement(null, null);
					String uri = term.apply(XSOMHelper.GetNamespace);
					String name = term.apply(XSOMHelper.GetName);
					if (_xsomHelper.getComplexType() == null) {
						throw new SAXException("Expected complex wrapper type for any in " + name);
					}
					term = _xsomHelper.matchElement(null, null);
					if (!_xsomHelper.isLastElementAny()) {
						throw new SAXException("Expected any type for " + name);
					}
					ch.startElement(uri, name, name, _atts);
					SQLXML sqlxml = (SQLXML) attribute;
					SAXSource saxSource = sqlxml.getSource(SAXSource.class);
					XMLReader xmlReader = saxSource.getXMLReader();
					xmlReader.setContentHandler(this);
					xmlReader.parse(saxSource.getInputSource());
					ch.endElement(uri, name, name);
					_xsomHelper.endAny();
				} else if (attribute != null) {
					writeValue(attribute);
				} else {
					// TODO: nillable and required
				}
			}
			ch.endElement(_uri, _name, _name);
			_xsomHelper.endComplex();
		}

		private void writeValue(Object attribute) throws SAXException, SQLException {
			final ContentHandler ch = getContentHandler();
			final XSTerm term = _xsomHelper.matchElement(null, null);
			final XSSimpleType simpleType = _xsomHelper.getSimpleType();
			if (simpleType == null) {
				throw new SAXException("Expected simple type for " + attribute);
			}
			final String uri = term.apply(XSOMHelper.GetNamespace);
			final String name = term.apply(XSOMHelper.GetName);
			ch.startElement(uri, name, name, _atts);
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
			ch.characters(value.toCharArray(), 0, value.length());
			ch.endElement(uri, name, name);
		}

	}

}
