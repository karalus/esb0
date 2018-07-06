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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;
import javax.json.JsonWriter;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.eclipse.persistence.oxm.MediaType;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.jdbc.JDBCParameter;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;

public abstract class JDBCAction extends TerminalAction {

	protected final DataSource _dataSource;
	protected final String _sql;
	private final List<JDBCParameter> _params;

	public JDBCAction(String dsName, String sql, List<JDBCParameter> params) throws NamingException {
		InitialContext initialContext = new InitialContext();
		try {
			_dataSource = (DataSource) initialContext.lookup(dsName);
		} finally {
			initialContext.close();
		}
		_sql = sql;
		_params = params;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		if (inPipeline) {
			for (JDBCParameter param : _params) {
				if (param.isBody()) {
					switch (param.getType()) {
					case Types.CLOB:
					case Types.BLOB:
						return super.prepare(context, message, inPipeline);
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
		ps.setQueryTimeout((int) message.getTimeleft() / 1000);
	}
	
	protected final static void extractResult(Statement statement, ESBMessage message) throws SQLException {
		JsonArrayBuilder builder = Json.createArrayBuilder();
		JsonArray result = null;
		int updateCount;
		for (;; statement.getMoreResults()) {
			updateCount = statement.getUpdateCount();
			ResultSet resultSet = statement.getResultSet();
			if (updateCount >= 0) {
				builder.add(updateCount);
			} else if (resultSet != null) {
				result = createJsonArray(resultSet);
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
			jsonWriter.writeArray(result);
			jsonWriter.close();
			message.getHeaders().clear();
			message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, MediaType.APPLICATION_JSON.getMediaType());
			message.reset(BodyType.STRING, sw.toString());
		} else if (updateCount >= 0) {
			message.getVariables().put("sqlUpdateCount", updateCount);
		}
	}
	
	protected final static JsonArray createJsonArray(ResultSet resultSet) throws SQLException {
		ResultSetMetaData metaData = resultSet.getMetaData();
		JsonArrayBuilder result = Json.createArrayBuilder();
		while (resultSet.next()) {
			JsonObjectBuilder builder = Json.createObjectBuilder();
			for (int i = 1; i <= metaData.getColumnCount(); ++i) {
				Object value = resultSet.getObject(i);
				if (!resultSet.wasNull()) {
					String name = metaData.getColumnLabel(i);
					switch (metaData.getColumnType(i)) {
					case Types.SMALLINT:
					case Types.INTEGER:
						builder.add(name, ((Number) value).intValue());
						break;
					case Types.BIT:
						builder.add(name, ((Boolean) value));
						break;
					default:
						builder.add(name, value.toString());
						break;
					}
				}
			}
			result.add(builder);
		}
		return result.build();
	}
	
}
