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

import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLXML;
import java.sql.Types;
import java.util.List;

import javax.naming.NamingException;
import javax.xml.transform.sax.SAXSource;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.jdbc.JDBCParameter;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;

public class JDBCProcedureAction extends JDBCAction {

	private final List<JDBCParameter> _outParams;

	public JDBCProcedureAction(GlobalContext globalContext, String dsName, String sql, List<JDBCParameter> inParams, List<JDBCParameter> outParams, int maxRows, int timeout) throws NamingException {
		super(globalContext, dsName, sql, inParams, maxRows, timeout);
		_outParams = outParams;
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		super.execute(context, execContext, message, nextActionIsPipelineStop);

		final String sql = (String) bindVariable(_sql != null ? _sql : message.getBodyAsString(context), context, message); 
		logger.debug("JDBCProcedureAction sql=" + sql);
		try (Connection conn = getConnection(execContext);
				AutoCloseable timer = context.getTimeGauge().createTimer("prepareCall & execute");
				CallableStatement cs = conn.prepareCall(sql)) {

			bindParameters(cs, context, execContext, message);
			for (JDBCParameter param : _outParams) {
				cs.registerOutParameter(param.getPos(), param.getType());
			}
			cs.execute();
			for (JDBCParameter param : _outParams) {
				if (param.isBody()) {
					switch (param.getType()) {
					case Types.SQLXML:
						SQLXML sqlxml = cs.getSQLXML(param.getPos());
						message.reset(BodyType.SOURCE, sqlxml.getSource(SAXSource.class));
						break;
					case Types.CLOB:
						message.reset(BodyType.READER, cs.getCharacterStream(param.getPos()));
						break;
					case Types.BLOB:
						final Blob blob = cs.getBlob(param.getPos());
						message.reset(BodyType.BYTES, blob.getBytes(1, (int) blob.length()));
						blob.free();
						break;
					default:
						throw new ExecutionException(this, "SQL type for body not supported: " + param.getTypeName());
					}
				} else {
					message.getVariables().put(param.getBindName(), cs.getObject(param.getPos()));
				}
			}
			extractResult(cs, message);
		}
	}

}
