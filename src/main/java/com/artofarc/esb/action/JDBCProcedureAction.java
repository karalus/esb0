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

import java.sql.*;
import static java.sql.Types.*;
import java.util.List;

import javax.naming.NamingException;
import javax.xml.transform.sax.SAXSource;
import javax.xml.xquery.XQItem;

import org.eclipse.persistence.jaxb.dynamic.DynamicJAXBContext;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.jdbc.JDBCConnection;
import com.artofarc.esb.jdbc.JDBCParameter;
import com.artofarc.esb.jdbc.JDBCResult;
import com.artofarc.esb.jdbc.JDBCXMLMapper;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;

public class JDBCProcedureAction extends JDBCAction {

	private final List<JDBCParameter> _outParams;

	public JDBCProcedureAction(GlobalContext globalContext, String dsName, String sql, List<JDBCParameter> inParams, List<JDBCParameter> outParams, int maxRows, int timeout, DynamicJAXBContext jaxbContext) throws NamingException {
		super(globalContext, dsName, sql, inParams, maxRows, timeout, jaxbContext);
		checkParameters(outParams);
		_outParams = outParams;
	}

	@Override
	protected JDBCResult executeStatement(Context context, ExecutionContext execContext, ESBMessage message) throws Exception {
		final String sql = (String) bindVariable(_sql != null ? _sql : message.getBodyAsString(context), context, message); 
		logger.debug("JDBCProcedureAction sql=" + sql);
		JDBCConnection conn = execContext.getResource();
		try {
			CallableStatement cs = conn.getConnection().prepareCall(sql);
			for (JDBCParameter param : _outParams) {
				if (param.getXmlElement() != null) {
					cs.registerOutParameter(param.getPos(), param.getType(), _mapper.getTypeName(param.getXmlElement().getNamespaceURI(), param.getXmlElement().getLocalPart()));
				} else {
					cs.registerOutParameter(param.getPos(), param.getType());
				}
			}
			bindParameters(conn, cs, context, execContext, message);
			cs.execute();
			for (JDBCParameter param : _outParams) {
				if (param.isBody()) {
					switch (param.getType()) {
					case SQLXML:
						SQLXML sqlxml = cs.getSQLXML(param.getPos());
						XQItem xqItem = context.getXQDataFactory().createItemFromDocument(sqlxml.getSource(SAXSource.class), null);
						message.reset(BodyType.XQ_ITEM, xqItem);
						sqlxml.free();
						break;
					case CLOB:
						message.reset(BodyType.READER, cs.getCharacterStream(param.getPos()));
						break;
					case BLOB:
						final Blob blob = cs.getBlob(param.getPos());
						message.reset(BodyType.BYTES, blob.getBytes(1, (int) blob.length()));
						blob.free();
						break;
					case STRUCT:
						Object jaxbElement = _mapper.fromJDBC((Struct) cs.getObject(param.getPos()), param.getXmlElement().getNamespaceURI(), param.getXmlElement().getLocalPart());
						message.marshal(context, _mapper.getJAXBContext().createMarshaller(), jaxbElement);
						break;
					default:
						throw new ExecutionException(this, "SQL type for body not supported: " + param.getTypeName());
					}
				} else if (param.isAttachments()) {
					Object jaxbElement = _mapper.fromJDBC((Struct) cs.getObject(param.getPos()), param.getXmlElement().getNamespaceURI(), param.getXmlElement().getLocalPart());
					JDBCXMLMapper.parseAttachments(jaxbElement, message);
				} else {
					message.getVariables().put(param.getBindName(), cs.getObject(param.getPos()));
				}
			}
			return execContext.setResource3(new JDBCResult(cs));
		} catch (Exception e) {
			conn.close();
			throw e;
		}
	}

}
