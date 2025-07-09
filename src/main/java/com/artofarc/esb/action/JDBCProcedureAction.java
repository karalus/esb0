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

import java.sql.CallableStatement;
import java.sql.SQLXML;
import java.sql.Struct;
import java.util.List;

import javax.xml.transform.sax.SAXSource;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.jdbc.*;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.sun.xml.xsom.XSSchemaSet;

public class JDBCProcedureAction extends JDBCAction {

	private final List<JDBCParameter> _outParams;

	public JDBCProcedureAction(GlobalContext globalContext, String dsName, String sql, List<JDBCParameter> inParams, List<JDBCParameter> outParams, Boolean moreThanOneResult, int maxRows, int timeout, String keepConnection, XSSchemaSet schemaSet) {
		super(globalContext, dsName, sql, inParams, moreThanOneResult != null ? moreThanOneResult : false, maxRows, timeout, keepConnection, schemaSet);
		checkParameters(outParams);
		_outParams = outParams;
	}

	@Override
	protected JDBCResult executeStatement(Context context, ExecutionContext execContext, ESBMessage message, String sql) throws Exception {
		JDBCConnection conn = execContext.getResource();
		CallableStatement cs = conn.getConnection().prepareCall(sql);
		for (JDBCParameter param : _outParams) {
			if (param.getXmlElement() != null) {
				JDBC2XMLMapper mapper = new JDBC2XMLMapper(_schemaSet, param.getXmlElement());
				cs.registerOutParameter(param.getPos(), param.getType().getVendorTypeNumber(), mapper.getTypeName());
			} else {
				cs.registerOutParameter(param.getPos(), param.getType().getVendorTypeNumber());
			}
		}
		bindParameters(conn, cs, context, execContext, message);
		cs.execute();
		for (JDBCParameter param : _outParams) {
			if (param.isBody()) {
				boolean free = param.isFree() != null ? param.isFree() : JDBCParameter.FREE_DEFAULT;
				switch (param.getType()) {
				case SQLXML:
					SQLXML sqlxml = cs.getSQLXML(param.getPos());
					message.materializeBodyFromSource(context, sqlxml.getSource(SAXSource.class));
					sqlxml.free();
					break;
				case CLOB:
					message.reset(BodyType.READER, free ? new ClobReader(cs.getClob(param.getPos())) : cs.getCharacterStream(param.getPos()));
					break;
				case BLOB:
					message.reset(BodyType.INPUT_STREAM, free ? new BlobInputStream(cs.getBlob(param.getPos())) : cs.getBlob(param.getPos()).getBinaryStream());
					break;
				case STRUCT:
					JDBC2XMLMapper mapper = new JDBC2XMLMapper(_schemaSet, param.getXmlElement());
					message.materializeBodyFromSource(context, mapper.createSAXSource(context, (Struct) cs.getObject(param.getPos())));
					break;
				default:
					throw new ExecutionException(this, "SQL type for body not supported: " + param.getType());
				}
				message.setContentType(null);
				message.setCharset(null);
			} else if (param.isAttachments()) {
				Struct struct = (Struct) cs.getObject(param.getPos());
				if (struct != null) {
					JDBCAttachments jdbcAttachments = new JDBCAttachments(_schemaSet, param.getXmlElement().getNamespaceURI(), param.getXmlElement().getLocalPart());
					jdbcAttachments.parseAttachments(struct, message);
				}
			} else {
				message.getVariables().put(param.getBindName(), cs.getObject(param.getPos()));
			}
		}
		return new JDBCResult(cs);
	}

}
