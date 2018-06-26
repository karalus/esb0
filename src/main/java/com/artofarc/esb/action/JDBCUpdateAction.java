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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.jdbc.JDBCParameter;
import com.artofarc.esb.message.ESBMessage;

public class JDBCUpdateAction extends Action {

	private final DataSource _dataSource;
	private final String _sql;

	private List<JDBCParameter> _params;

	public JDBCUpdateAction(String dsName, String sql, List<JDBCParameter> params) throws NamingException {
		InitialContext initialContext = new InitialContext();
		try {
			_dataSource = (DataSource) initialContext.lookup(dsName);
		} finally {
			initialContext.close();
		}
		// TODO: Check if no of parameters is matching the sql
		_sql = sql;
		_params = params;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		Connection connection = _dataSource.getConnection();
		ExecutionContext execContext = new ExecutionContext(connection);
		try (AutoCloseable timer = context.getTimeGauge().createTimer("prepareStatement & execute")) {
			PreparedStatement ps = connection.prepareStatement(_sql);
			for (JDBCParameter param : _params) {
				if (param.isBody()) {
					switch (param.getType()) {
					case Types.CLOB:
						final String bodyAsString = param.alignValue(message.getBodyAsString(context));
						ps.setCharacterStream(param.getPos(), new StringReader(bodyAsString));
						break;
					case Types.BLOB:
						final byte[] msgBody = param.alignValue(message.getBodyAsByteArray(context));
						ps.setBinaryStream(param.getPos(), new ByteArrayInputStream(msgBody), msgBody.length);
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
			int updateCount = ps.executeUpdate();
			execContext.setResource2(ps);
			message.getVariables().put("sqlUpdateCount", updateCount);
		} catch (SQLException e) {
			connection.close();
			throw e;
		}
		return execContext;
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		if (nextActionIsPipelineStop) {
			// Result result = message.getBodyAsSinkResult();
			// message.reset(BodyType.READER, execContext.getResource3());
			// message.writeTo(result, context);
		}
	}

	@Override
	protected void close(ExecutionContext execContext) throws SQLException {
		try {
			PreparedStatement ps = execContext.getResource2();
			ps.close();
		} finally {
			Connection connection = execContext.getResource();
			connection.close();
		}
	}

}
