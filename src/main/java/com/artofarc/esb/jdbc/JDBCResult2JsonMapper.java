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

import java.sql.*;
import java.util.GregorianCalendar;

import static java.sql.Types.*;
import java.math.BigDecimal;

import javax.json.*;
import javax.xml.bind.DatatypeConverter;

import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.StringWriter;

public class JDBCResult2JsonMapper {

	public static void extractResult(Statement statement, ESBMessage message) throws SQLException {
		JsonStructure result = null;
		JsonArrayBuilder builder = Json.createArrayBuilder();
		for (boolean moreResults = true;; moreResults = statement.getMoreResults()) {
			int updateCount = statement.getUpdateCount();
			ResultSet resultSet = statement.getResultSet();
			if (updateCount >= 0) {
				builder.add(updateCount);
			} else if (moreResults && resultSet != null) {
				result = createJson(resultSet);
				builder.add(result);
			} else {
				break;
			}
		}
		JsonArray jsonArray = builder.build();
		if (jsonArray.size() > 1) {
			result = jsonArray;
		}
		if (result != null) {
			StringWriter sw = new StringWriter();
			JsonWriter jsonWriter = Json.createWriter(sw);
			jsonWriter.write(result);
			jsonWriter.close();
			message.getHeaders().clear();
			message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, HttpConstants.HTTP_HEADER_CONTENT_TYPE_JSON);
			message.reset(BodyType.READER, sw.getStringReader());
		} else if (jsonArray.size() > 0) {
			message.getVariables().put("sqlUpdateCount", jsonArray.getInt(0));
		}
	}

	protected static JsonObject createJson(ResultSet resultSet) throws SQLException {
		ResultSetMetaData metaData = resultSet.getMetaData();
		final int colSize = metaData.getColumnCount();
		JsonObjectBuilder result = Json.createObjectBuilder();
		JsonArrayBuilder header = Json.createArrayBuilder();
		for (int i = 1; i <= colSize; ++i) {
			int scale = metaData.getScale(i);
			String type = JDBCParameter.CODES.get(metaData.getColumnType(i)) + '(' + metaData.getPrecision(i) + (scale > 0 ? ", " + scale : "" + ')');
			header.add(Json.createObjectBuilder().add(metaData.getColumnLabel(i), type));
		}
		result.add("header", header);
		JsonArrayBuilder rows = Json.createArrayBuilder();
		while (resultSet.next()) {
			JsonArrayBuilder row = Json.createArrayBuilder();
			for (int i = 1; i <= colSize; ++i) {
				switch (metaData.getColumnType(i)) {
				case SMALLINT:
				case INTEGER:
					int integer = resultSet.getInt(i);
					if (checkNotNull(resultSet, row)) {
						row.add(integer);
					}
					break;
				case BIT:
					boolean bool = resultSet.getBoolean(i);
					if (checkNotNull(resultSet, row)) {
						row.add(bool);
					}
					break;
				case NUMERIC:
				case DECIMAL:
					BigDecimal bigDecimal = resultSet.getBigDecimal(i);
					if (checkNotNull(resultSet, row)) {
						row.add(bigDecimal);
					}
					break;
				case TIMESTAMP:
					Timestamp timestamp = resultSet.getTimestamp(i);
					if (checkNotNull(resultSet, row)) {
						row.add(DatatypeConverter.printDateTime(convert(timestamp)));
					}
					break;
				case DATE:
					Date date = resultSet.getDate(i);
					if (checkNotNull(resultSet, row)) {
						row.add(DatatypeConverter.printDate(convert(date)));
					}
					break;
				case BLOB:
					Blob blob = resultSet.getBlob(i);
					if (checkNotNull(resultSet, row)) {
						row.add(DatatypeConverter.printBase64Binary(blob.getBytes(1, (int) blob.length())));
						blob.free();
					}
					break;
				case CLOB:
					Clob clob = resultSet.getClob(i);
					if (checkNotNull(resultSet, row)) {
						row.add(clob.getSubString(1, (int) clob.length()));
						clob.free();
					}
					break;
				case VARBINARY:
				case LONGVARBINARY:
					byte[] bytes = resultSet.getBytes(i);
					if (checkNotNull(resultSet, row)) {
						row.add(DatatypeConverter.printBase64Binary(bytes));
					}
					break;
				default:
					Object value = resultSet.getObject(i);
					if (checkNotNull(resultSet, row)) {
						row.add(value.toString());
					}
					break;
				}
			}
			rows.add(row);
		}
		result.add("rows", rows);
		return result.build();
	}

	private static boolean checkNotNull(ResultSet resultSet, JsonArrayBuilder row) throws SQLException {
		if (resultSet.wasNull()) {
			row.addNull();
			return false;
		} else {
			return true;
		}
	}

	private static GregorianCalendar convert(java.util.Date date) {
		GregorianCalendar gc = new GregorianCalendar();
		gc.setTime(date);
		return gc;
	}

}
