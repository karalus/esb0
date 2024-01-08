/*
 * Copyright 2023 Andre Karalus
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
package com.artofarc.esb.action;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.jdbc.JDBCConnection;
import com.artofarc.esb.jdbc.JDBCParameter;
import com.artofarc.esb.jdbc.JDBCResult;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.ReflectionUtils;

public class JDBCSQLAction extends JDBCAction {

	private final String[] _generatedKeys;

	public JDBCSQLAction(GlobalContext globalContext, String dsName, String sql, List<JDBCParameter> params, List<String> generatedKeys, Boolean moreThanOneResult, int maxRows, int timeout, String keepConnection) {
		super(globalContext, dsName, sql, params, moreThanOneResult != null ? moreThanOneResult : true, maxRows, timeout, keepConnection, null);
		_generatedKeys = generatedKeys.isEmpty() ? null : generatedKeys.toArray(new String[generatedKeys.size()]);
		if (sql != null) {
			int count = 0;
			for (int i = 0;; ++i, ++count) {
				i = ReflectionUtils.findNextDelim(sql, i, "?");
				if (i < 0) break;
			}
			if (params.size() != count) {
				throw new IllegalArgumentException("Number of ? is not matching parameter count");
			}
		}
	}

	@Override
	protected JDBCResult executeStatement(Context context, ExecutionContext execContext, ESBMessage message, String sql) throws Exception {
		JDBCConnection conn = execContext.getResource();
		PreparedStatement ps = _generatedKeys != null ? conn.getConnection().prepareStatement(sql, _generatedKeys) : conn.getConnection().prepareStatement(sql);
		bindParameters(conn, ps, context, execContext, message);
		ps.execute();
		if (_generatedKeys != null) {
			ResultSet generatedKeys = ps.getGeneratedKeys();
			int i = 0;
			while (generatedKeys.next()) {
				message.putVariable(_generatedKeys[i++], generatedKeys.getObject(1));
			}
			generatedKeys.close();
		}
		return new JDBCResult(ps);
	} 

}
