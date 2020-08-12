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

import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Timestamp;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.bind.DatatypeConverter;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.sax.TransformerHandler;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

import com.artofarc.util.Collections;
import com.artofarc.util.JAXPFactoryHelper;
import com.artofarc.util.PrefixHandler;
import com.artofarc.util.XSOMHelper;
import com.sun.xml.xsom.XSSchemaSet;
import com.sun.xml.xsom.XSSimpleType;
import com.sun.xml.xsom.XSTerm;

public final class XML2JDBCMapper extends PrefixHandler {

	private final XSSchemaSet _schemaSet;
	private final JDBCConnection _connection;
	private final StringBuilder _builder = new StringBuilder(128);
	private final ArrayDeque<DBObject> _stack = new ArrayDeque<>();
	private final ArrayDeque<Map.Entry<Integer, String>> _prefixPos = new ArrayDeque<>();

	private XSOMHelper xsomHelper;
	private boolean root = true, complex;
	private String primitiveType;
	private int anyLevel;
	private Object object;
	private TransformerHandler delegate;
	private PrefixHandler delegatePrefixHandler;

	private static final class DBObject {
		String name;
		boolean array;
		final ArrayList<Object> objects = new ArrayList<>();

		void add(String localName, Object object) {
			JDBCConnection.logger.debug(name + " adding " + localName + " at " + objects.size() + ": " + object);
			objects.add(object);
		}

		Object create(JDBCConnection connection) throws SAXException {
			if (name == null) {
				return objects.get(0);
			}
			try {
				if (array) {
					return connection.createArray(name, objects.toArray());
				} else {
					return connection.getConnection().createStruct(name, objects.toArray());
				}
			} catch (SQLException e) {
				throw new SAXException("Could not create " + this, e);
			}
		}

		@Override
		public String toString() {
			return (array ? "Array " : "Struct ") + name + " attributes " + objects.size();
		}
	}

	public XML2JDBCMapper(XSSchemaSet schemaSet, JDBCConnection connection) {
		_schemaSet = schemaSet;
		_connection = connection;
	}

	public Object getObject() {
		return object;
	}

	@Override
	public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
		_builder.setLength(0);
		if (delegate != null) {
			int i = qName.indexOf(':');
			String prefix = i > 0 ? qName.substring(0, i) : XMLConstants.DEFAULT_NS_PREFIX;
			if (delegatePrefixHandler.getNamespace(prefix) == null) {
				startPrefixMapping(prefix, getNamespace(prefix));
				_prefixPos.push(Collections.createEntry(anyLevel, prefix));
			}
			delegate.startElement(uri, localName, qName, atts);
			++anyLevel;
			return;
		}
		if (root) {
			root = false;
			xsomHelper = new XSOMHelper(_schemaSet.getElementDecl(uri, localName));
			complex = true;
		} else {
			for (;;) {
				XSTerm term = xsomHelper.nextElement();
				if (xsomHelper.isLastElementAny()) {
					DBObject dbObject = _stack.peek();
					dbObject.name = null;
					try {
						SQLXML sqlxml = _connection.getConnection().createSQLXML();
						delegate = JAXPFactoryHelper.newTransformerHandler();
						delegate.setResult(sqlxml.setResult(DOMResult.class));
						delegatePrefixHandler = new PrefixHandler();
						dbObject.add(localName, sqlxml);
					} catch (SQLException | TransformerConfigurationException e) {
						throw new SAXException(e);
					}
					delegate.startDocument();
					startElement(uri, localName, qName, atts);
					return;
				}
				String name = term.apply(XSOMHelper.GetName);
				if (localName.equals(name)) {
					break;
				} else {
					_stack.peek().add(name, null);
					if (xsomHelper.getComplexType() != null) {
						xsomHelper.endComplex();
					}
				}
			}
			complex = xsomHelper.getComplexType() != null;
			final String nil = atts.getValue(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "nil");
			if (nil != null && DatatypeConverter.parseBoolean(nil)) {
				if (complex) {
					xsomHelper.endComplex();
					complex = false;
				}
				primitiveType = "nil";
			} else {
				final XSSimpleType simpleType = xsomHelper.getSimpleType();
				primitiveType = simpleType != null ? XSOMHelper.getJsonType(simpleType) : null;
			}
		}
		if (xsomHelper.isLastElementRepeated()) {
			_stack.peek().array = true;
			xsomHelper.repeatElement();
		}
		if (complex) {
			DBObject dbObject = new DBObject();
			dbObject.name = xsomHelper.getComplexType().getName();
			_stack.push(dbObject);
		}
	}

	@Override
	public void endElement(String uri, String localName, String qName) throws SAXException {
		if (delegate != null) {
			delegate.endElement(uri, localName, qName);
			--anyLevel;
			Map.Entry<Integer, String> entry = _prefixPos.peek();
			if (entry != null && entry.getKey() == anyLevel) {
				_prefixPos.pop();
				endPrefixMapping(entry.getValue());
			}
			if (anyLevel == 0) {
				delegate.endDocument();
				delegate = null;
				delegatePrefixHandler = null;
				xsomHelper.endAny();
			}
			return;
		}
		if (primitiveType == null) {
			if (_stack.peek().array) {
				xsomHelper.endArray();
			}
			for (int level = _stack.size();;) {
				XSTerm term = xsomHelper.nextElement();
				if (term == null || level > xsomHelper.getLevel()) {
					if (term != null) {
						xsomHelper.push(term);
					}
					break;
				}
				if (xsomHelper.isLastElementAny()) {
					xsomHelper.endAny();
				} else {
					if (xsomHelper.isLastElementRepeated()) {
						_stack.peek().array = true;
						xsomHelper.endArray();
						break;
					} else {
						_stack.peek().add(term.apply(XSOMHelper.GetName), null);
						if (xsomHelper.getComplexType() != null) {
							xsomHelper.endComplex();
						}
					}
				}
			}
			object = _stack.pop().create(_connection);
		} else {
			final String s = _builder.toString();
			switch (primitiveType) {
			case "int":
				// is faster
				object = DatatypeConverter.parseInt(s);
				break;
			case "long":
				object = Long.parseLong(s);
				break;
			case "integer":
			case "decimal":
				object = new BigDecimal(s);
				break;
			case "boolean":
				// covers '1' and '0'
				object = DatatypeConverter.parseBoolean(s);
				break;
			case "nil":
				object = null;
				break;
			case "base64Binary":
				try {
					Blob blob = _connection.getConnection().createBlob();
					blob.setBytes(1, DatatypeConverter.parseBase64Binary(s));
					object = blob;
				} catch (SQLException e) {
					throw new SAXException(e);
				}
				break;
			case "date":
			case "dateTime":
				if (s.isEmpty()) {
					// Special logic for compatibility with Oracle DatabaseAdapter
					object = null;
				} else {
					object = new Timestamp(DatatypeConverter.parseDateTime(s).getTimeInMillis());
				}
				break;
			default:
				object = s;
				break;
			}
		}
		DBObject dbObject = _stack.peek();
		if (dbObject != null) {
			dbObject.add(localName, object);
		}
		primitiveType = null;
		_builder.setLength(0);
	}

	@Override
	public void characters(char[] ch, int start, int length) throws SAXException {
		if (delegate != null) {
			delegate.characters(ch, start, length);
		} else {
			_builder.append(ch, start, length);
		}
	}

	@Override
	public void startPrefixMapping(String prefix, String uri) throws SAXException {
		if (delegate != null) {
			delegate.startPrefixMapping(prefix, uri);
			delegatePrefixHandler.startPrefixMapping(prefix, uri);
		} else {
			super.startPrefixMapping(prefix, uri);
		}
	}

	@Override
	public void endPrefixMapping(String prefix) throws SAXException {
		if (delegate != null) {
			delegate.endPrefixMapping(prefix);
			delegatePrefixHandler.endPrefixMapping(prefix);
		} else {
			super.endPrefixMapping(prefix);
		}
	}

}
