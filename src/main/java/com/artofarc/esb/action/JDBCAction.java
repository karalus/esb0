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

import javax.json.stream.JsonGenerator;
import javax.sql.DataSource;
import javax.xml.transform.dom.DOMResult;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.jdbc.*;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.JsonValueGenerator;
import com.sun.xml.xsom.XSSchemaSet;

public abstract class JDBCAction extends Action {

	private final String _dsName;
	private final String _sql;
	private final List<JDBCParameter> _params;
	private final int _maxRows;
	private final Integer _timeout;
	private final String _keepConnection;
	protected XSSchemaSet _schemaSet;

	JDBCAction(GlobalContext globalContext, String dsName, String sql, List<JDBCParameter> params, int maxRows, int timeout, String keepConnection, XSSchemaSet schemaSet) {
		_pipelineStop = true;
		_dsName = dsName != null ? dsName.intern() : null;
		_sql = sql;
		_params = params;
		_maxRows = maxRows;
		_timeout = timeout;
		_keepConnection = keepConnection.intern();
		_schemaSet = schemaSet;
		checkParameters(params);
	}

	protected final void checkParameters(List<JDBCParameter> params) {
		boolean body = false, attachments = false;
		for (JDBCParameter jdbcParameter : params) {
			if (jdbcParameter.isBody()) {
				if (body) {
					throw new IllegalArgumentException("Cannot have more than one parameter of type body");
				}
				body = true;
			} else if (jdbcParameter.isAttachments()) {
				if (attachments) {
					throw new IllegalArgumentException("Cannot have more than one parameter of type attachments");
				}
				attachments = true;
			} else if (jdbcParameter.getBindName() == null) {
				throw new IllegalArgumentException("Parameter must bind to variable, body or attachment");
			}
			if (_schemaSet == null && jdbcParameter.getType() == STRUCT) {
				throw new IllegalArgumentException("When using parameter type STRUCT, a schema is mandatory");
			}
		}
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		boolean keepConnection = Boolean.parseBoolean(bindVariable(_keepConnection, context, message).toString());
		JDBCConnection connection = message.getVariable(ESBConstants.JDBCConnection);
		if (connection == null) {
			if (_dsName == null) {
				throw new ExecutionException(this, "No DataSource configured and no Connection kept");
			}
			String dsName = (String) bindVariable(_dsName, context, message);
			DataSource dataSource = (DataSource) context.getGlobalContext().getProperty(dsName);
			if (dataSource == null) {
				throw new ExecutionException(this, "DataSource not found: " + dsName);
			}
			connection = new JDBCConnection(dataSource.getConnection(), keepConnection);
			if (keepConnection) {
				message.putVariable(ESBConstants.JDBCConnection, connection);
			}
		} else if (!keepConnection) {
			message.getVariables().remove(ESBConstants.JDBCConnection);
		}
		ExecutionContext execContext = new ExecutionContext(connection);
		if (inPipeline) {
			for (JDBCParameter param : _params) {
				if (param.isBody()) {
					switch (param.getType()) {
					case SQLXML:
						SQLXML xmlObject = connection.createSQLXML();
						message.reset(BodyType.RESULT, xmlObject.setResult(DOMResult.class));
						execContext.setResource2(xmlObject);
						break;
					case CLOB:
						Clob clob = connection.createClob();
						message.reset(BodyType.WRITER, clob.setCharacterStream(1L));
						execContext.setResource2(clob);
						break;
					case BLOB:
						Blob blob = connection.createBlob();
						message.reset(BodyType.OUTPUT_STREAM, blob.setBinaryStream(1L));
						message.setCharset(message.getSinkEncoding());						
						execContext.setResource2(blob);
						break;
					case STRUCT:
						break;
					default:
						throw new ExecutionException(this, "SQL type for body not supported: " + param.getTypeName());
					}
					break;
				}
			}
		}
		return execContext;
	}

	abstract protected JDBCResult executeStatement(Context context, ExecutionContext execContext, ESBMessage message, String sql) throws Exception;

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		String sql = (String) bindVariable(_sql != null ? _sql : message.getBodyAsString(context), context, message); 
		logger.debug("JDBCAction sql=" + sql);
		if (sql.length() > 0) {
			try (JDBCResult result = executeStatement(context, execContext, message, sql)) {
				if (result.hasComplexContent()) {
					message.clearHeaders();
					if (message.isSink()) {
						try (JsonGenerator jsonGenerator = message.getBodyAsJsonGenerator()) {
							result.writeJson(jsonGenerator);
						}
					} else {
						JsonValueGenerator jsonValueGenerator = new JsonValueGenerator();
						result.writeJson(jsonValueGenerator);
						message.reset(BodyType.JSON_VALUE, jsonValueGenerator.getJsonValue());
					}
					message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, HttpConstants.HTTP_HEADER_CONTENT_TYPE_JSON);
				} else if (result.getCurrentUpdateCount() >= 0) {
					message.getVariables().put(JDBCResult.SQL_UPDATE_COUNT, result.getCurrentUpdateCount());
				}
			}
		}
	}

	@Override
	protected void close(ExecutionContext execContext, ESBMessage message, boolean exception) throws Exception  {
		boolean keepConnection = message.getVariables().containsKey(ESBConstants.JDBCConnection);
		if (exception || !keepConnection) {
			JDBCConnection connection = execContext.getResource();
			connection.close(!exception);
		}
	}

	public static void closeKeptConnection(ESBMessage message, boolean commit) throws SQLException {
		JDBCConnection connection = (JDBCConnection) message.getVariables().remove(ESBConstants.JDBCConnection);
		if (connection != null) {
			connection.close(commit);
		}
	}

	protected final void bindParameters(JDBCConnection conn, PreparedStatement ps, Context context, ExecutionContext execContext, ESBMessage message) throws Exception {
		for (JDBCParameter param : _params) {
			if (param.isBody()) {
				switch (param.getType()) {
				case SQLXML:
					SQLXML xmlObject = execContext.getResource2();
					if (xmlObject == null) {
						xmlObject = conn.createSQLXML();
						message.writeTo(xmlObject.setResult(DOMResult.class), context);
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
						if (message.getBodyType() == BodyType.INVALID) {
							ps.setNull(param.getPos(), CLOB);
						} else {
							if (param.getTruncate() == null) {
								ps.setCharacterStream(param.getPos(), message.getBodyAsReader(context));
							} else {
								ps.setCharacterStream(param.getPos(), new StringReader(param.truncate(message.getBodyAsString(context))));
							}
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
						if (message.getBodyType() == BodyType.INVALID) {
							ps.setNull(param.getPos(), BLOB);
						} else {
							Long length = message.getByteLength();
							if (param.getTruncate() == null || length != null && length <= param.getTruncate()) {
								ps.setBinaryStream(param.getPos(), message.getBodyAsInputStream(context));
							} else {
								ps.setBinaryStream(param.getPos(), new ByteArrayInputStream(message.getBodyAsByteArray(context), 0, param.getTruncate()));
							}
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
					ps.setObject(param.getPos(), param.alignValue(value, conn), param.getType());
				} else {
					ps.setNull(param.getPos(), param.getType());
				}
			}
		}
		int ceil = (message.getTimeleft(_timeout).intValue() + 999) / 1000;
		ps.setQueryTimeout(ceil);
		ps.setMaxRows(_maxRows);
	}

}
