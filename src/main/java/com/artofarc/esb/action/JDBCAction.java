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
import java.math.BigDecimal;
import java.sql.*;
import static java.sql.Types.*;
import java.util.GregorianCalendar;
import java.util.List;

import javax.json.*;
import javax.naming.NamingException;
import javax.sql.DataSource;
import javax.xml.bind.DatatypeConverter;
import javax.xml.transform.sax.SAXResult;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.jdbc.JDBCParameter;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.StringWriter;

public abstract class JDBCAction extends TerminalAction {

	private final DataSource _dataSource;
	protected final String _sql;
	private final List<JDBCParameter> _params;
	private final int _maxRows;
	private final Integer _timeout;

	public JDBCAction(GlobalContext globalContext, String dsName, String sql, List<JDBCParameter> params, int maxRows, int timeout) throws NamingException {
		_dataSource = globalContext.lookup(dsName);
		_sql = sql;
		_params = params;
		_maxRows = maxRows;
		_timeout = timeout;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		ExecutionContext execContext = null;
		if (inPipeline) {
			for (JDBCParameter param : _params) {
				if (param.isBody()) {
					Connection connection = _dataSource.getConnection();
					switch (param.getType()) {
					case SQLXML:
						SQLXML xmlObject = connection.createSQLXML();
						message.reset(BodyType.RESULT, xmlObject.setResult(SAXResult.class));
						execContext = new ExecutionContext(xmlObject);
						break;
					case CLOB:
					case BLOB:
						execContext = super.prepare(context, message, true);
						break;
					default:
						throw new ExecutionException(this, "SQL type for body not supported: " + param.getTypeName());
					}
					execContext.setResource2(connection);
				}
			}
		}
		return execContext;
	}

	protected final Connection getConnection(ExecutionContext execContext) throws SQLException {
		Connection connection = execContext != null ? execContext.<Connection> getResource2() : null;
		if (connection == null) {
			connection = _dataSource.getConnection();
		}
		return connection;
	}

	protected final void bindParameters(PreparedStatement ps, Context context, ExecutionContext execContext, ESBMessage message) throws Exception {
		for (JDBCParameter param : _params) {
			if (param.isBody()) {
				switch (param.getType()) {
				case SQLXML:
					ps.setSQLXML(param.getPos(), execContext.<SQLXML>getResource());
					break;
				case CLOB:
					if (param.getTruncate() == null) {
						ps.setCharacterStream(param.getPos(), message.getBodyAsReader(context));
					} else {
						ps.setCharacterStream(param.getPos(), new StringReader(param.<String> alignValue(message.getBodyAsString(context))));
					}
					break;
				case BLOB:
					if (param.getTruncate() == null) {
						ps.setBinaryStream(param.getPos(), message.getBodyAsInputStream(context));
					} else {
						ps.setBinaryStream(param.getPos(), new ByteArrayInputStream(param.<byte[]> alignValue(message.getBodyAsByteArray(context))));
					}
					break;
				default:
					throw new ExecutionException(this, "SQL type for body not supported: " + param.getTypeName());
				}
			} else {
				ps.setObject(param.getPos(), param.alignValue(resolve(message, param.getBindName(), false)), param.getType());
			}
		}
		ps.setQueryTimeout(message.getTimeleft(_timeout).intValue() / 1000);
		ps.setMaxRows(_maxRows);
	}

	protected static void extractResult(Statement statement, ESBMessage message) throws SQLException {
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
