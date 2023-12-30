/*
 * Copyright 2022 Andre Karalus
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

import java.sql.*;
import static java.sql.Types.*;

import java.util.ArrayDeque;
import java.util.Iterator;
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
	protected final XSSchemaSet _schemaSet;

	JDBCAction(GlobalContext globalContext, String dsName, String sql, List<JDBCParameter> params, int maxRows, int timeout, String keepConnection, XSSchemaSet schemaSet) {
		_pipelineStop = true;
		_offeringSink = isOfferingSink(params);
		_streamingToSink = true;
		_dsName = dsName != null ? dsName.intern() : null;
		_sql = sql;
		checkParameters(_params = params);
		_maxRows = maxRows;
		_timeout = timeout;
		_keepConnection = keepConnection.intern();
		_schemaSet = schemaSet;
	}

	protected final void checkParameters(List<JDBCParameter> params) {
		if (_sql == null && params.size() > 0) {
			throw new IllegalArgumentException("sql is mandatory if parameters are used");
		}
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
			if (_schemaSet == null && jdbcParameter.getType() == JDBCType.STRUCT) {
				throw new IllegalArgumentException("When using parameter type STRUCT, a schema is mandatory");
			}
		}
	}

	private static boolean isOfferingSink(List<JDBCParameter> params) {
		for (JDBCParameter param : params) {
			if (param.isBody()) {
				switch (param.getType()) {
				case SQLXML:
				case CLOB:
				case BLOB:
					return true;
				default:
					return false;
				}
			}
		}
		return false;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		String dsName = _dsName != null ? (String) eval(_dsName, context, message) : null;
		boolean keepConnection = Boolean.parseBoolean(eval(_keepConnection, context, message).toString());
		JDBCConnection connection = null;
		ArrayDeque<JDBCConnection> connections = context.getResource(ESBConstants.JDBCConnections);
		if (connections != null) {
			if (dsName == null) {
				connection = keepConnection ? connections.peek() : connections.poll();
			} else {
				for (Iterator<JDBCConnection> iter = connections.iterator(); iter.hasNext();) {
					JDBCConnection jdbcConnection = iter.next();
					if (jdbcConnection.getDsName().equals(dsName)) {
						connection = jdbcConnection;
						if (!keepConnection) {
							iter.remove();
						}
						break;
					}
				}
			}
		}
		if (connection == null) {
			if (dsName == null) {
				throw new ExecutionException(this, "No DataSource configured and no Connection kept");
			}
			DataSource dataSource = (DataSource) context.getGlobalContext().getProperty(dsName);
			if (dataSource == null) {
				throw new ExecutionException(this, "DataSource not found: " + dsName);
			}
			connection = new JDBCConnection(dsName, dataSource.getConnection(), keepConnection);
			if (keepConnection) {
				if (connections == null) {
					context.putResource(ESBConstants.JDBCConnections, connections = new ArrayDeque<>());
				}
				connections.push(connection);
			}
		}
		ExecutionContext execContext = new ExecutionContext(connection, keepConnection);
		if (_sql == null) {
			execContext.setResource3(message.getBodyAsString(context));
		} else if (inPipeline) {
			for (JDBCParameter param : _params) {
				if (param.isBody()) {
					try {
						switch (param.getType()) {
						case SQLXML:
							SQLXML xmlObject = connection.createSQLXML();
							message.reset(BodyType.RESULT, xmlObject.setResult(DOMResult.class));
							execContext.setResource3(xmlObject);
							break;
						case CLOB:
							Clob clob = connection.createClob();
							message.reset(BodyType.WRITER, clob.setCharacterStream(1L));
							execContext.setResource3(clob);
							break;
						case BLOB:
							Blob blob = connection.createBlob();
							message.reset(BodyType.OUTPUT_STREAM, blob.setBinaryStream(1L));
							message.setCharset(message.getSinkEncoding());						
							execContext.setResource3(blob);
							break;
						case STRUCT:
							break;
						default:
							throw new ExecutionException(this, "SQL type for body not supported: " + param.getType());
						}
					} catch (Exception e) {
						connection.close(false);
						throw e;
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
		String sql = (String) eval(_sql != null ? _sql : execContext.getResource3(), context, message); 
		logger.debug("JDBCAction sql={}", sql);
		if (sql.length() > 0) {
			try (JDBCResult result = executeStatement(context, execContext, message, sql)) {
				if (result.hasComplexContent()) {
					message.clearHeaders();
					if (message.isSink()) {
						try (JsonGenerator jsonGenerator = message.createJsonGeneratorFromBodyAsSink()) {
							result.writeJson(jsonGenerator);
						}
					} else {
						JsonValueGenerator jsonValueGenerator = new JsonValueGenerator();
						result.writeJson(jsonValueGenerator);
						message.reset(BodyType.JSON_VALUE, jsonValueGenerator.getJsonValue());
					}
					message.setContentType(HttpConstants.HTTP_HEADER_CONTENT_TYPE_JSON);
				} else if (result.getCurrentUpdateCount() >= 0) {
					message.getVariables().put(JDBCResult.SQL_UPDATE_COUNT, result.getCurrentUpdateCount());
				}
			}
		}
	}

	@Override
	protected void close(Context context, ExecutionContext execContext, boolean exception) throws Exception  {
		JDBCConnection connection = execContext.getResource();
		boolean connectionKept = execContext.getResource2();
		if (!connectionKept) {
			connection.close(!exception);
		} else if (exception) {
			ArrayDeque<JDBCConnection> connections = context.getResource(ESBConstants.JDBCConnections);
			connections.remove(connection);
			connection.close(false);
		}
	}

	public static void closeKeptConnections(Context context, boolean commit) throws SQLException {
		ArrayDeque<JDBCConnection> connections = context.removeResource(ESBConstants.JDBCConnections);
		if (connections != null) {
			for (JDBCConnection connection : connections) {
				connection.close(commit);
			}
		}
	}

	protected final void bindParameters(JDBCConnection conn, PreparedStatement ps, Context context, ExecutionContext execContext, ESBMessage message) throws Exception {
		for (JDBCParameter param : _params) {
			if (param.isBody()) {
				switch (param.getType()) {
				case SQLXML:
					SQLXML xmlObject = execContext.getResource3();
					if (xmlObject == null) {
						xmlObject = conn.createSQLXML();
						message.writeTo(xmlObject.setResult(DOMResult.class), context);
					}
					ps.setSQLXML(param.getPos(), xmlObject);
					break;
				case CLOB:
					Clob clob = execContext.getResource3();
					if (clob != null) {
						if (param.getTruncate() != null) {
							clob.truncate(param.getTruncate());
						}
						ps.setClob(param.getPos(), clob);
					} else {
						if (message.getBodyType() == BodyType.INVALID) {
							ps.setNull(param.getPos(), CLOB);
						} else {
							ps.setCharacterStream(param.getPos(), message.getBodyAsReader(context));
						}
					}
					break;
				case BLOB:
					Blob blob = execContext.getResource3();
					if (blob != null) {
						if (param.getTruncate() != null) {
							blob.truncate(param.getTruncate());
						}
						ps.setBlob(param.getPos(), blob);
					} else {
						if (message.getBodyType() == BodyType.INVALID) {
							ps.setNull(param.getPos(), BLOB);
						} else {
							ps.setBinaryStream(param.getPos(), message.getBodyAsInputStream(context));
						}
					}
					break;
				case STRUCT:
					XML2JDBCMapper mapper = new XML2JDBCMapper(_schemaSet, conn);
					message.writeToSAX(mapper, context);
					ps.setObject(param.getPos(), mapper.getObject());
					break;
				default:
					throw new ExecutionException(this, "SQL type for body not supported: " + param.getType());
				}
			} else if (param.isAttachments()) {
				if (message.getAttachments().size() > 0) {
					JDBCAttachments jdbcAttachments = new JDBCAttachments(_schemaSet, param.getXmlElement().getNamespaceURI(), param.getXmlElement().getLocalPart());
					ps.setObject(param.getPos(), jdbcAttachments.createAttachments(message, conn));
				}
			} else {
				param.setParameter(ps, resolve(message, param.getBindName(), false), conn);
			}
		}
		int ceil = (message.getTimeleft(_timeout).intValue() + 999) / 1000;
		ps.setQueryTimeout(ceil);
		ps.setMaxRows(_maxRows);
	}

}
