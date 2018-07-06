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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import javax.naming.NamingException;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.jdbc.JDBCParameter;
import com.artofarc.esb.message.ESBMessage;

public class JDBCSQLAction extends JDBCAction {

	public JDBCSQLAction(String dsName, String sql, List<JDBCParameter> params) throws NamingException {
		super(dsName, sql, params);
		if (sql != null) {
	      int count = 0;
	      for (int i = sql.length(); i > 0;) {
	         // Not handling escape \?
	         if (sql.charAt(--i) == '?') ++count;
	      }
			if (params.size() != count) {
				throw new IllegalArgumentException("Number of ? is not matching parameter count");
			}
		}
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		super.execute(context, execContext, message, nextActionIsPipelineStop);

		final String sql = bindVariable(_sql != null ? _sql : message.getBodyAsString(context), message); 
		logger.fine("JDBCSQLAction sql=" + sql);
		Connection connection = _dataSource.getConnection();
		try (AutoCloseable timer = context.getTimeGauge().createTimer("prepareStatement & execute"); PreparedStatement ps = connection.prepareStatement(sql)) {
			bindParameters(ps, context, message);
			ps.execute();
			extractResult(ps, message);
		} finally {
			connection.close();
		}
	}

}
