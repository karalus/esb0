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
import java.sql.*;
import static java.sql.Types.*;
import java.util.List;

import javax.naming.NamingException;
import javax.sql.DataSource;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.jdbc.JDBCAttachments;
import com.artofarc.esb.jdbc.JDBCConnection;
import com.artofarc.esb.jdbc.JDBCParameter;
import com.artofarc.esb.jdbc.JDBCResult;
import com.artofarc.esb.jdbc.JDBCResult2JsonMapper;
import com.artofarc.esb.jdbc.XML2JDBCMapper;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.sun.xml.xsom.XSSchemaSet;

public abstract class JDBCAction extends Action {

	private final String _dsName;
	protected final String _sql;
	private final List<JDBCParameter> _params;
	private final int _maxRows;
	private final Integer _timeout;
	protected XSSchemaSet _schemaSet;

	JDBCAction(GlobalContext globalContext, String dsName, String sql, List<JDBCParameter> params, int maxRows, int timeout, XSSchemaSet schemaSet) throws NamingException {
		if (!dsName.contains("${")) {
			globalContext.getProperty(dsName);
		}
		_pipelineStop = false;
		for (JDBCParameter param : params) {
			_pipelineStop |= param.isBody();
		}
		_dsName = dsName;
		_sql = sql;
		_params = params;
		_maxRows = maxRows;
		_timeout = timeout;
		_schemaSet = schemaSet;
		checkParameters(params);
	}

	protected final void checkParameters(List<JDBCParameter> params) {
		if (_schemaSet == null) {
			for (JDBCParameter jdbcParameter : params) {
				if (jdbcParameter.getType() == STRUCT) {
					throw new IllegalArgumentException("When using parameter type STRUCT, a schema is mandatory");
				}
			}
		}
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		String dsName = (String) bindVariable(_dsName, context, message);
		DataSource dataSource = (DataSource) context.getGlobalContext().getProperty(dsName);
		JDBCConnection connection = new JDBCConnection(dataSource.getConnection());
		ExecutionContext execContext = new ExecutionContext(connection);
		if (inPipeline) {
			for (JDBCParameter param : _params) {
				if (param.isBody()) {
					switch (param.getType()) {
					case SQLXML:
						SQLXML xmlObject = connection.getConnection().createSQLXML();
						message.reset(BodyType.RESULT, connection.createSAXResult(xmlObject));
						execContext.setResource2(xmlObject);
						break;
					case CLOB:
						Clob clob = connection.getConnection().createClob();
						message.reset(BodyType.WRITER, clob.setCharacterStream(1L));
						execContext.setResource2(clob);
						break;
					case BLOB:
						Blob blob = connection.getConnection().createBlob();
						message.reset(BodyType.OUTPUT_STREAM, blob.setBinaryStream(1L));
						message.setCharset(message.getSinkEncoding());						
						execContext.setResource2(blob);
						break;
					case STRUCT:
						break;
					default:
						throw new ExecutionException(this, "SQL type for body not supported: " + param.getTypeName());
					}
				}
			}
		}
		if (!_pipelineStop) {
			JDBCResult result = executeStatement(context, execContext, message);
			if (result.hasComplexContent()) {
				message.clearHeaders();
			}
		}
		return execContext;
	}

	abstract protected JDBCResult executeStatement(Context context, ExecutionContext execContext, ESBMessage message) throws Exception;

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		if (_pipelineStop) {
			JDBCResult result = executeStatement(context, execContext, message);
			if (result.hasComplexContent()) {
				message.clearHeaders();
			}
		}
		try (JDBCConnection connection = execContext.getResource(); JDBCResult result = execContext.getResource3()) {
			JDBCResult2JsonMapper.writeResult(result, message);
		}
	}

	protected final void bindParameters(JDBCConnection conn, PreparedStatement ps, Context context, ExecutionContext execContext, ESBMessage message) throws Exception {
		for (JDBCParameter param : _params) {
			if (param.isBody()) {
				switch (param.getType()) {
				case SQLXML:
					SQLXML xmlObject = execContext.getResource2();
					if (xmlObject == null) {
						xmlObject = conn.getConnection().createSQLXML();
						message.writeToSAX(conn.createSAXResult(xmlObject).getHandler(), context);
					}
					ps.setSQLXML(param.getPos(), xmlObject);
					break;
				case CLOB:
					Clob clob = execContext.getResource2();
					if (clob != null) {
						if (param.getTruncate() != null) {
							clob.truncate(param.getTruncate());
						}
						ps.setClob(param.getPos(), clob);
					} else {
						if (param.getTruncate() == null) {
							ps.setCharacterStream(param.getPos(), message.getBodyAsReader(context));
						} else {
							ps.setCharacterStream(param.getPos(), new StringReader((String) param.alignValue(message.getBodyAsString(context))));
						}
					}
					break;
				case BLOB:
					Blob blob = execContext.getResource2();
					if (blob != null) {
						if (param.getTruncate() != null) {
							blob.truncate(param.getTruncate());
						}
						ps.setBlob(param.getPos(), blob);
					} else {
						if (param.getTruncate() == null) {
							ps.setBinaryStream(param.getPos(), message.getBodyAsInputStream(context));
						} else {
							ps.setBinaryStream(param.getPos(), new ByteArrayInputStream((byte[]) param.alignValue(message.getBodyAsByteArray(context))));
						}
					}
					break;
				case STRUCT:
					XML2JDBCMapper mapper = new XML2JDBCMapper(_schemaSet, conn);
					message.writeToSAX(mapper, context);
					ps.setObject(param.getPos(), mapper.getObject());
					break;
				default:
					throw new ExecutionException(this, "SQL type for body not supported: " + param.getTypeName());
				}
			} else if (param.isAttachments()) {
				if (message.getAttachments().size() > 0) {
					JDBCAttachments jdbcAttachments = new JDBCAttachments(_schemaSet, param.getXmlElement().getNamespaceURI(), param.getXmlElement().getLocalPart());
					ps.setObject(param.getPos(), jdbcAttachments.createAttachments(message, conn));
				}
			} else {
				Object value = resolve(message, param.getBindName(), false);
				if (value != null) {
					ps.setObject(param.getPos(), param.alignValue(value), param.getType());
				} else {
					ps.setNull(param.getPos(), param.getType());
				}
			}
		}
		ps.setQueryTimeout(message.getTimeleft(_timeout).intValue() / 1000);
		ps.setMaxRows(_maxRows);
	}

}
