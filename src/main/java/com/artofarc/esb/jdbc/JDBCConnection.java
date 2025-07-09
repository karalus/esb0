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

	@FunctionalInterface
	public interface SQLResource {
		void free() throws SQLException;
	}

	private final String _dsName;
	private final Connection _connection;
	private final boolean _isOracleConnection;
	private final ArrayList<SQLResource> _resources = new ArrayList<>();

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
		for (SQLResource resource : _resources) {
			try {
				resource.free();
			} catch (SQLException e) {
				logger.warn("Could not free resource", e);
			}
		}
		if (_dsName != null) {
			try {
				if (!_connection.getAutoCommit()) {
					if (commit) {
						_connection.commit();
					} else {
						_connection.rollback();
					}
				}
			} finally {
				_connection.close();
			}
		}
	}

	public void logResource(SQLResource resource) {
		_resources.add(resource);
	}

	public Blob createBlob() throws SQLException {
		Blob blob = _connection.createBlob();
		_resources.add(blob::free);
		return blob;
	}

	public Clob createClob() throws SQLException {
		Clob clob = _connection.createClob();
		_resources.add(clob::free);
		return clob;
	}

	public SQLXML createSQLXML() throws SQLException {
		SQLXML sqlxml = _connection.createSQLXML();
		_resources.add(sqlxml::free);
		return sqlxml;
	}

	public Array createArray(String typeName, Object[] elements) throws SQLException {
		if (!_isOracleConnection) {
			throw new SQLException("Only works with OJDBC");
		}
		// https://docs.oracle.com/cd/B28359_01/java.111/b31224/oraarr.htm#i1059642
		try {
			Array array = (Array) createARRAY.invoke(_connection.unwrap(ifcOracleConnection), typeName, (Object) elements);
			_resources.add(array::free);
			return array;
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
