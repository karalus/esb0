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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLXML;

import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.TransformerHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.artofarc.util.ReflectionUtils;

/**
 * Encapsulate the oddities of OJDBC.
 */
public final class JDBCConnection implements AutoCloseable {

	protected final static Logger logger = LoggerFactory.getLogger(JDBCConnection.class);

	private static Class<?> ifcOracleConnection;
	private static Method createARRAY;
	private static Method getSQLTypeName;
	private static Class<?> jxTransformerHandler;

	static {
		try {
			ifcOracleConnection = Class.forName("oracle.jdbc.OracleConnection");
			createARRAY = ifcOracleConnection.getMethod("createARRAY", String.class, Object.class);
			getSQLTypeName = Class.forName("oracle.sql.ARRAY").getMethod("getSQLTypeName");
		} catch (ReflectiveOperationException e) {
			logger.warn("Oracle JDBC driver not in classpath. Mapping of Arrays will not work");
		}
		try {
			jxTransformerHandler = Class.forName("oracle.xml.jaxp.JXTransformerHandler");
		} catch (ReflectiveOperationException e) {
			logger.warn("Oracle XML parser not in classpath. Binding of SQLXML will not work when using namespaces");
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
		try {
			return (Array) createARRAY.invoke(_connection.unwrap(ifcOracleConnection), typeName, elements);
		} catch (InvocationTargetException | IllegalAccessException e) {
			throw ReflectionUtils.convert(e.getCause(), SQLException.class);
		}
	}

	public static String getSQLTypeName(Array array) throws SQLException {
		try {
			return (String) getSQLTypeName.invoke(array);
		} catch (InvocationTargetException | IllegalAccessException e) {
			throw ReflectionUtils.convert(e.getCause(), SQLException.class);
		}
	}

	public TransformerHandler getTransformerHandler() {
		if (_isOracleConnection && jxTransformerHandler != null) {
			try {
				return (TransformerHandler) jxTransformerHandler.newInstance();
			} catch (InstantiationException | IllegalAccessException e) {
				throw new RuntimeException(e);
			}
		}
		return null;
	}

	public SAXResult createSAXResult(SQLXML xmlObject) throws SQLException {
		SAXResult result = xmlObject.setResult(SAXResult.class);
		TransformerHandler transformerHandler = getTransformerHandler();
		if (transformerHandler != null) {
			transformerHandler.setResult(result);
			result = new SAXResult(transformerHandler);
		}
		return result;
	}

}
