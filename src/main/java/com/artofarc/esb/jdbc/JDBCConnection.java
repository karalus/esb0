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

import java.lang.reflect.Method;
import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.util.ArrayList;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.transform.sax.SAXResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.XMLFilterImpl;

import com.artofarc.util.Collections;
import com.artofarc.util.ReflectionUtils;

/**
 * Encapsulate the oddities of OJDBC.
 */
public final class JDBCConnection implements AutoCloseable {

	protected final static Logger logger = LoggerFactory.getLogger(JDBCConnection.class);

	private static Class<?> ifcOracleConnection;
	private static Method createARRAY;
	private static Method getSQLTypeName;

	static {
		try {
			ifcOracleConnection = Class.forName("oracle.jdbc.OracleConnection");
			createARRAY = ifcOracleConnection.getMethod("createARRAY", String.class, Object.class);
			getSQLTypeName = Class.forName("oracle.sql.ARRAY").getMethod("getSQLTypeName");
		} catch (ReflectiveOperationException e) {
			logger.warn("Oracle JDBC driver not in classpath. Mapping of Arrays will not work");
		}
	}

	private static final class AddAttributeForPrefixMappingFilter extends XMLFilterImpl {

		private final ArrayList<Map.Entry<String, String>> _prefixes = new ArrayList<>();

		@Override
		public void startPrefixMapping(String prefix, String uri) throws SAXException {
			super.startPrefixMapping(prefix, uri);
			_prefixes.add(Collections.createEntry(prefix, uri));
		}

		@Override
		public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
			if (_prefixes.isEmpty()) {
				super.startElement(uri, localName, qName, atts);
			} else {
				AttributesImpl attributes = new AttributesImpl(atts);
				for (Map.Entry<String, String> entry : _prefixes) {
					String prefix = entry.getKey();
					String qNameAtt = prefix.isEmpty() ? "xmlns" : "xmlns:" + prefix;
					attributes.addAttribute(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, prefix, qNameAtt, "CDATA", entry.getValue());
				}
				_prefixes.clear();
				super.startElement(uri, localName, qName, attributes);
			}
		}
	}

	private final Connection _connection;
	private final boolean _isOracleConnection;

	public JDBCConnection(Connection connection) throws SQLException {
		_connection = connection;
		_isOracleConnection = ifcOracleConnection != null && connection.isWrapperFor(ifcOracleConnection);
	}

	public Connection getConnection() {
		return _connection;
	}

	@Override
	public void close() throws SQLException {
		_connection.close();
	}

	public Array createArray(String typeName, Object[] elements) throws SQLException {
		if (!_isOracleConnection) {
			throw new SQLException("Only works with OJDBC");
		}
		// https://docs.oracle.com/cd/B28359_01/java.111/b31224/oraarr.htm#i1059642
		return ReflectionUtils.invoke(createARRAY, SQLException.class, _connection.unwrap(ifcOracleConnection), typeName, elements);
	}

	public static String getSQLTypeName(Array array) throws SQLException {
		return ReflectionUtils.invoke(getSQLTypeName, SQLException.class, array);
	}

	public SAXResult createSAXResult(SQLXML xmlObject) throws SQLException {
		SAXResult result = xmlObject.setResult(SAXResult.class);
		if (_isOracleConnection) {
			// OJDBC expects attributes for namespace declarations
			AddAttributeForPrefixMappingFilter filter = new AddAttributeForPrefixMappingFilter();
			filter.setContentHandler(result.getHandler());
			result.setHandler(filter);
		}
		return result;
	}

}
