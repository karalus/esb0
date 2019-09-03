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
import javax.xml.bind.JAXBElement;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.sax.SAXResult;

import org.eclipse.persistence.dynamic.DynamicEntity;
import org.eclipse.persistence.jaxb.dynamic.DynamicJAXBContext;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.jdbc.JDBCParameter;
import com.artofarc.esb.jdbc.JDBCXMLMapper;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;

public abstract class JDBCAction extends TerminalAction {

	private final DataSource _dataSource;
	protected final String _sql;
	private final List<JDBCParameter> _params;
	private final int _maxRows;
	private final Integer _timeout;
	protected final JDBCXMLMapper _mapper;

	public JDBCAction(GlobalContext globalContext, String dsName, String sql, List<JDBCParameter> params, int maxRows, int timeout, DynamicJAXBContext jaxbContext) throws NamingException {
		_dataSource = globalContext.lookup(dsName);
		_sql = sql;
		_params = params;
		_maxRows = maxRows;
		_timeout = timeout;
		_mapper = jaxbContext != null ? new JDBCXMLMapper(jaxbContext) : null;
		checkParameters(params);
	}

	protected final void checkParameters(List<JDBCParameter> params) {
		if (_mapper == null) {
			for (JDBCParameter jdbcParameter : params) {
				if (jdbcParameter.getType() == STRUCT) {
					throw new IllegalArgumentException("When using parameter type STRUCT, a schema is mandatory");
				}
			}
		}
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		ExecutionContext execContext = null;
		if (inPipeline) {
			for (JDBCParameter param : _params) {
				if (param.isBody()) {
					switch (param.getType()) {
					case SQLXML:
						Connection connection = _dataSource.getConnection();
						SQLXML xmlObject = connection.createSQLXML();
						message.reset(BodyType.RESULT, xmlObject.setResult(SAXResult.class));
						execContext = new ExecutionContext(xmlObject, connection);
						break;
					case CLOB:
					case BLOB:
						execContext = super.prepare(context, message, true);
						break;
					case STRUCT:
						execContext = new ExecutionContext(null, _dataSource.getConnection());
						break;
					default:
						throw new ExecutionException(this, "SQL type for body not supported: " + param.getTypeName());
					}
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
					ps.setSQLXML(param.getPos(), execContext.<SQLXML> getResource());
					break;
				case CLOB:
					if (param.getTruncate() == null) {
						ps.setCharacterStream(param.getPos(), message.getBodyAsReader(context));
					} else {
						ps.setCharacterStream(param.getPos(), new StringReader((String) param.alignValue(message.getBodyAsString(context))));
					}
					break;
				case BLOB:
					if (param.getTruncate() == null) {
						ps.setBinaryStream(param.getPos(), message.getBodyAsInputStream(context));
					} else {
						ps.setBinaryStream(param.getPos(), new ByteArrayInputStream((byte[]) param.alignValue(message.getBodyAsByteArray(context))));
					}
					break;
				case STRUCT:
					Unmarshaller unmarshaller = _mapper.getJAXBContext().createUnmarshaller();
					Object root = message.unmarshal(context, unmarshaller);
					if (root instanceof DynamicEntity) {
						DynamicEntity entity = (DynamicEntity) root;
						ps.setObject(param.getPos(), JDBCXMLMapper.toJDBC(entity, true, getConnection(execContext)));
					} else {
						JAXBElement<?> jaxbElement = (JAXBElement<?>) root;
						ps.setObject(param.getPos(), JDBCXMLMapper.toJDBC(jaxbElement, false, getConnection(execContext)));
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

}
