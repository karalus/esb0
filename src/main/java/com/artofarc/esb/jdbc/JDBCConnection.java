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
package com.artofarc.esb.jdbc;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.sql.*;
import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.artofarc.util.ReflectionUtils;

/**
 * Encapsulate the oddities of OJDBC.
 */
public final class JDBCConnection {

	protected final static Logger logger = LoggerFactory.getLogger(JDBCConnection.class);

	private static Class<?> ifcOracleConnection;
	private static Class<?> ifcORADataFactory;
	private static Class<?> ifcORAData;
	private static MethodHandle createARRAY;
	private static MethodHandle getSQLTypeName;

	static {
		try {
			ifcOracleConnection = Class.forName("oracle.jdbc.OracleConnection");
			ifcORADataFactory = Class.forName("oracle.sql.ORADataFactory");
			ifcORAData = Class.forName("oracle.sql.ORAData");
			MethodHandles.Lookup lookup = MethodHandles.publicLookup();
			createARRAY = lookup.unreflect(ifcOracleConnection.getMethod("createARRAY", String.class, Object.class));
			getSQLTypeName = lookup.unreflect(Class.forName("oracle.sql.ARRAY").getMethod("getSQLTypeName"));
		} catch (ReflectiveOperationException e) {
			logger.warn("Oracle JDBC driver not in classpath. Mapping of Arrays and dealing with AQjmsAdtMessage will not work");
		}
	}

	private final String _dsName;
	private final Connection _connection;
	private final boolean _isOracleConnection;
	private final ArrayList<Object> _resources = new ArrayList<>();

	public JDBCConnection(String dsName, Connection connection, boolean manualCommit) throws SQLException {
		_dsName = dsName;
		_connection = connection;
		_isOracleConnection = ifcOracleConnection != null && connection.isWrapperFor(ifcOracleConnection);
		if (manualCommit && connection.getAutoCommit()) {
			connection.setAutoCommit(false);
		}
	}

	public String getDsName() {
		return _dsName;
	}

	public Connection getConnection() {
		return _connection;
	}

	public void close(boolean commit) throws SQLException {
		for (Object resource : _resources) {
			try {
				ReflectionUtils.invoke(resource.getClass().getMethod("free"), SQLException.class, resource);
			} catch (NoSuchMethodException | SQLException e) {
				logger.warn("Could not free resource", e);
			}
		}
		if (_dsName != null) {
			if (commit && !_connection.getAutoCommit()) {
				_connection.commit();
			}
			_connection.close();
		}
	}

	private <T> T logResource(T resource) {
		_resources.add(resource);
		return resource;
	}

	public Blob createBlob() throws SQLException {
		return logResource(_connection.createBlob());
	}

	public Clob createClob() throws SQLException {
		return logResource(_connection.createClob());
	}

	public SQLXML createSQLXML() throws SQLException {
		return logResource(_connection.createSQLXML());
	}

	public Array createArray(String typeName, Object[] elements) throws SQLException {
		if (!_isOracleConnection) {
			throw new SQLException("Only works with OJDBC");
		}
		// https://docs.oracle.com/cd/B28359_01/java.111/b31224/oraarr.htm#i1059642
		try {
			return logResource((Array) createARRAY.invoke(_connection.unwrap(ifcOracleConnection), typeName, (Object) elements));
		} catch (Throwable e) {
			throw ReflectionUtils.convert(e, SQLException.class);
		}
	}

	public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
		return _connection.createStruct(typeName, attributes);
	}

	public static String getSQLTypeName(Array array) throws SQLException {
		if (!array.getClass().getName().equals("oracle.sql.ARRAY")) {
			throw new SQLException("Only works with oracle.sql.ARRAY");
		}
		try {
			return (String) getSQLTypeName.invoke(array);
		} catch (Throwable e) {
			throw ReflectionUtils.convert(e, SQLException.class);
		}
	}

	public static Class<?> getIfcORADataFactory() {
		return ifcORADataFactory;
	}

	public static Class<?> getIfcORAData() {
		return ifcORAData;
	}

}
