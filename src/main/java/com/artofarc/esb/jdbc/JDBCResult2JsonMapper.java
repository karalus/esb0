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
import java.math.BigDecimal;
import java.sql.*;
import static java.sql.Types.*;

import javax.json.stream.JsonGenerator;
import javax.xml.bind.DatatypeConverter;

import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.StringWriter;

public class JDBCResult2JsonMapper {

	public static void writeResult(JDBCResult result, ESBMessage message) throws SQLException, IOException {
		if (result.hasComplexContent()) {
			StringWriter sw = null;
			if (!message.isSink()) {
				message.reset(BodyType.WRITER, sw = new StringWriter());
			}
			try (JsonGenerator jsonGenerator = message.getBodyAsJsonGenerator()) {
				writeStartCurrent(result, jsonGenerator);
				if (result.next()) {
					jsonGenerator.writeStartArray("more");
					do {
						writeStartCurrent(result, jsonGenerator);
						jsonGenerator.writeEnd();
					} while (result.next());
					jsonGenerator.writeEnd();
				}
				jsonGenerator.writeEnd();
			}
			if (sw != null) {
				message.reset(BodyType.READER, sw.getStringReader());
			}
			message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, HttpConstants.HTTP_HEADER_CONTENT_TYPE_JSON);
		} else if (result.getCurrentUpdateCount() >= 0) {
			message.getVariables().put(JDBCResult.SQL_UPDATE_COUNT, result.getCurrentUpdateCount());
		}
	}

	private static void writeStartCurrent(JDBCResult result, JsonGenerator jsonGenerator) throws SQLException {
		if (result.getCurrentUpdateCount() >= 0) {
			jsonGenerator.writeStartObject();
			jsonGenerator.write(JDBCResult.SQL_UPDATE_COUNT, result.getCurrentUpdateCount());
		} else {
			writeJson(result.getCurrentResultSet(), jsonGenerator);
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
						json.write(DatatypeConverter.printDateTime(JDBCParameter.convert(timestamp)));
					}
					break;
				case DATE:
					Date date = resultSet.getDate(i);
					if (checkNotNull(resultSet, json)) {
						json.write(DatatypeConverter.printDate(JDBCParameter.convert(date)));
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
