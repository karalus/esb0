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

import java.math.BigDecimal;
import java.sql.*;
import static java.sql.Types.*;
import java.util.GregorianCalendar;

import javax.json.stream.JsonGenerator;
import javax.xml.bind.DatatypeConverter;

public final class JDBCResult implements AutoCloseable {

	public static final String SQL_UPDATE_COUNT = "sqlUpdateCount";

	private final Statement _statement;
	private final int firstUpdateCount;
	private int currentUpdateCount = -1;
	private ResultSet currentResultSet;
	private Boolean readAhead;

	public JDBCResult(Statement statement) throws SQLException {
		_statement = statement;
		try {
			currentResultSet = statement.getResultSet();
		} catch (SQLException e) {
			// https://stackoverflow.com/questions/42091653/whats-the-correct-way-to-fetch-all-possibly-implicit-results-from-an-oracle-q
			if (e.getErrorCode() == 17283) {
				if (_statement.getMoreResults()) {
					currentResultSet = statement.getResultSet();
				}
			} else {
				throw e;
			}
		}
		if (currentResultSet == null) {
			firstUpdateCount = statement.getUpdateCount();
			readAhead = firstUpdateCount >= 0 && next();
		} else {
			firstUpdateCount = -1;
		}
	}

	public boolean next() throws SQLException {
		if (readAhead != null) {
			boolean next = readAhead;
			readAhead = null;
			return next;
		}
		if (_statement.getMoreResults()) {
			currentResultSet = _statement.getResultSet();
			currentUpdateCount = -1;
			return true;
		} else {
			currentResultSet = null;
			currentUpdateCount = _statement.getUpdateCount();
			return currentUpdateCount >= 0;
		}
	}

	public int getCurrentUpdateCount() {
		if (readAhead != null) {
			return firstUpdateCount;
		}
		return currentUpdateCount;
	}

	public ResultSet getCurrentResultSet() {
		return currentResultSet;
	}

	public boolean hasComplexContent() {
		return currentResultSet != null || readAhead;
	}

	@Override
	public void close() throws SQLException {
		_statement.close();
	}

	public void writeJson(JsonGenerator jsonGenerator) throws SQLException {
		writeStartCurrent(jsonGenerator);
		if (next()) {
			jsonGenerator.writeStartArray("more");
			do {
				writeStartCurrent(jsonGenerator);
				jsonGenerator.writeEnd();
			} while (next());
			jsonGenerator.writeEnd();
		}
		jsonGenerator.writeEnd();
	}

	private void writeStartCurrent(JsonGenerator jsonGenerator) throws SQLException {
		if (getCurrentUpdateCount() >= 0) {
			jsonGenerator.writeStartObject();
			jsonGenerator.write(JDBCResult.SQL_UPDATE_COUNT, getCurrentUpdateCount());
		} else {
			writeJson(getCurrentResultSet(), jsonGenerator);
		}
	}

	private static void writeJson(ResultSet resultSet, JsonGenerator json) throws SQLException {
		ResultSetMetaData metaData = resultSet.getMetaData();
		final int colSize = metaData.getColumnCount();
		json.writeStartObject();
		json.writeStartArray("header");
		for (int i = 1; i <= colSize; ++i) {
			int precision = metaData.getPrecision(i);
			int scale = metaData.getScale(i);
			String type = JDBCParameter.CODES.get(metaData.getColumnType(i)) + (precision > 0 ? "(" + precision + (scale > 0 ? ", " + scale : "") + ')' : "");
			json.writeStartObject();
			json.write(metaData.getColumnLabel(i), type);
			json.writeEnd();
		}
		json.writeEnd();
		GregorianCalendar calendar = new GregorianCalendar(JDBCParameter.TIME_ZONE);
		json.writeStartArray("rows");
		while (resultSet.next()) {
			json.writeStartArray();
			for (int i = 1; i <= colSize; ++i) {
				switch (metaData.getColumnType(i)) {
				case SMALLINT:
				case INTEGER:
					int integer = resultSet.getInt(i);
					if (checkNotNull(resultSet, json)) {
						json.write(integer);
					}
					break;
				case BIT:
					boolean bool = resultSet.getBoolean(i);
					if (checkNotNull(resultSet, json)) {
						json.write(bool);
					}
					break;
				case NUMERIC:
				case DECIMAL:
					BigDecimal bigDecimal = resultSet.getBigDecimal(i);
					if (checkNotNull(resultSet, json)) {
						json.write(bigDecimal);
					}
					break;
				case TIMESTAMP:
					Timestamp timestamp = resultSet.getTimestamp(i);
					if (checkNotNull(resultSet, json)) {
						calendar.setTime(timestamp);
						json.write(DatatypeConverter.printDateTime(calendar));
					}
					break;
				case DATE:
					Date date = resultSet.getDate(i);
					if (checkNotNull(resultSet, json)) {
						calendar.setTime(date);
						json.write(DatatypeConverter.printDate(calendar));
					}
					break;
				case BLOB:
					Blob blob = resultSet.getBlob(i);
					if (checkNotNull(resultSet, json)) {
						json.write(DatatypeConverter.printBase64Binary(blob.getBytes(1, (int) blob.length())));
						blob.free();
					}
					break;
				case CLOB:
					Clob clob = resultSet.getClob(i);
					if (checkNotNull(resultSet, json)) {
						json.write(clob.getSubString(1, (int) clob.length()));
						clob.free();
					}
					break;
				case VARBINARY:
				case LONGVARBINARY:
					byte[] bytes = resultSet.getBytes(i);
					if (checkNotNull(resultSet, json)) {
						json.write(DatatypeConverter.printBase64Binary(bytes));
					}
					break;
				default:
					Object value = resultSet.getObject(i);
					if (checkNotNull(resultSet, json)) {
						json.write(value.toString());
					}
					break;
				}
			}
			json.writeEnd();
		}
		json.writeEnd();
	}

	private static boolean checkNotNull(ResultSet resultSet, JsonGenerator json) throws SQLException {
		if (resultSet.wasNull()) {
			json.writeNull();
			return false;
		} else {
			return true;
		}
	}

}
