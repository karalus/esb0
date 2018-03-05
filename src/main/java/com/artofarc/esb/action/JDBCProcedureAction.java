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

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import javax.xml.transform.Result;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.ESBVariableConstants;

public class JDBCProcedureAction extends Action {

	private final DataSource _dataSource;

	public JDBCProcedureAction(String dsName) throws NamingException {
		InitialContext initialContext = new InitialContext();
		try {
			_dataSource = (DataSource) initialContext.lookup(dsName);
		} finally {
			initialContext.close();
		}
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		// Operation is pkgName.procedure
		final String sql = "{call " + message.getVariable(ESBVariableConstants.operation) + "(?, ?, ?)}";
		logger.info("JDBCProcedureAction sql=" + sql);
		final Reader reader;
		if (message.getBodyType() == BodyType.INPUT_STREAM) {
			if (message.getCharsetName() != null) {
				reader = new InputStreamReader(message.<InputStream> getBody(), message.getCharsetName());
			} else {
				reader = new InputStreamReader(message.<InputStream> getBody());
			}
		} else if (message.getBodyType() == BodyType.READER) {
			reader = message.getBody();
		} else {
			reader = new StringReader(message.getBodyAsString(context));
		}
		Connection connection = _dataSource.getConnection();
		ExecutionContext execContext = new ExecutionContext(connection);
		try (AutoCloseable timer = context.getTimeGauge().createTimer("prepareCall & execute")) {
			CallableStatement cs = connection.prepareCall(sql);
			cs.setCharacterStream(1, reader);
			cs.registerOutParameter(2, Types.CLOB);
			cs.registerOutParameter(3, Types.BIT);
			cs.setQueryTimeout((int) message.getTimeleft() / 1000);
			cs.execute();
			execContext.setResource2(cs);
			message.reset(BodyType.READER, execContext.setResource3(cs.getCharacterStream(2)));
			message.getVariables().put(ESBVariableConstants.hasFault, cs.getBoolean(3));
		} catch (SQLException e) {
			connection.close();
			throw e;
		}
		return execContext;
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		if (nextActionIsPipelineStop) {
			Result result = message.getBodyAsSinkResult();
			message.reset(BodyType.READER, execContext.getResource3());
			message.writeTo(result, context);
		}
	}

	@Override
	protected void close(ExecutionContext execContext) throws SQLException {
		try {
			CallableStatement cs = execContext.getResource2();
			cs.close();
		} finally {
			Connection connection = execContext.getResource();
			connection.close();
		}
	}

}
