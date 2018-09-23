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
package com.artofarc.esb.action;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonStructure;
import javax.json.JsonWriter;
import javax.naming.NamingException;
import javax.sql.DataSource;
import javax.xml.bind.DatatypeConverter;

import org.eclipse.persistence.oxm.MediaType;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.jdbc.JDBCParameter;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;

public abstract class JDBCAction extends TerminalAction {

	protected final DataSource _dataSource;
	protected final String _sql;
	private final List<JDBCParameter> _params;
	private final int _fetchSize;

	public JDBCAction(GlobalContext globalContext, String dsName, String sql, List<JDBCParameter> params, int fetchSize) throws NamingException {
		_dataSource = globalContext.lookup(dsName);
		_sql = sql;
		_params = params;
		_fetchSize = fetchSize;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		if (inPipeline) {
			for (JDBCParameter param : _params) {
				if (param.isBody()) {
					switch (param.getType()) {
					case Types.CLOB:
					case Types.BLOB:
						return super.prepare(context, message, true);
					default:
						throw new ExecutionException(this, "SQL type for body not supported: " + param.getTypeName());
					}
				}
			}
		}
		return null;
	}

	protected void bindParameters(PreparedStatement ps, Context context, ESBMessage message) throws Exception {
		for (JDBCParameter param : _params) {
			if (param.isBody()) {
				switch (param.getType()) {
				case Types.CLOB:
					if (param.getTruncate() == null && message.isStream()) {
						ps.setCharacterStream(param.getPos(), message.getBodyAsReader(context));
					} else {
						ps.setCharacterStream(param.getPos(), new StringReader(param.<String> alignValue(message.getBodyAsString(context))));
					}
					break;
				case Types.BLOB:
					if (param.getTruncate() == null && message.getBodyType() == BodyType.INPUT_STREAM) {
						ps.setBinaryStream(param.getPos(), message.getUncompressedInputStream());
					} else {
						ps.setBinaryStream(param.getPos(), new ByteArrayInputStream(param.<byte[]> alignValue(message.getBodyAsByteArray(context))));
					}
					break;
				default:
					throw new ExecutionException(this, "SQL type for body not supported: " + param.getTypeName());
				}
			} else {
				Object value = message.getVariable(param.getBindName());
				if (value == null) {
					value = message.getHeader(param.getBindName());
				}
				ps.setObject(param.getPos(), param.alignValue(value), param.getType());
			}
		}
		ps.setQueryTimeout((int) (message.getTimeleft() / 1000L));
		ps.setFetchSize(_fetchSize);
	}
	
	protected final static void extractResult(Statement statement, ESBMessage message) throws SQLException {
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
			StringWriter sw = new StringWriter(ESBMessage.MTU);
			JsonWriter jsonWriter = Json.createWriter(sw);
			jsonWriter.write(result);
			jsonWriter.close();
			message.getHeaders().clear();
			message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, MediaType.APPLICATION_JSON.getMediaType());
			message.reset(BodyType.STRING, sw.toString());
		} else if (jsonArray.size() > 0) {
			message.getVariables().put("sqlUpdateCount", jsonArray.getInt(0));
		}
	}
	
	protected final static JsonObject createJson(ResultSet resultSet) throws SQLException {
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
				case Types.SMALLINT:
				case Types.INTEGER:
					int integer = resultSet.getInt(i);
					if (checkNotNull(resultSet, row)) {
						row.add(integer);
					}
					break;
				case Types.BIT:
					boolean bool = resultSet.getBoolean(i);
					if (checkNotNull(resultSet, row)) {
						row.add(bool);
					}
					break;
				case Types.NUMERIC:
				case Types.DECIMAL:
					BigDecimal bigDecimal = resultSet.getBigDecimal(i);
					if (checkNotNull(resultSet, row)) {
						row.add(bigDecimal);
					}
					break;
				case Types.TIMESTAMP:
					Timestamp timestamp = resultSet.getTimestamp(i);
					if (checkNotNull(resultSet, row)) {
						row.add(DatatypeConverter.printDateTime(convert(timestamp)));
					}
					break;
				case Types.DATE:
					java.sql.Date date = resultSet.getDate(i);
					if (checkNotNull(resultSet, row)) {
						row.add(DatatypeConverter.printDate(convert(date)));
					}
					break;
				case Types.BLOB:
					Blob blob = resultSet.getBlob(i);
					if (checkNotNull(resultSet, row)) {
						row.add(DatatypeConverter.printBase64Binary(blob.getBytes(1, (int) blob.length())));
					}
					break;
				case Types.VARBINARY:
				case Types.LONGVARBINARY:
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
	
	private static GregorianCalendar convert(Date date) {
		GregorianCalendar gc = new GregorianCalendar();
		gc.setTime(date);
		return gc;
	}
	
}
