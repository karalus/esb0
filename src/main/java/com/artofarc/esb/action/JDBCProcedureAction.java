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

import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.SQLXML;
import java.sql.Struct;
import java.util.List;

import javax.xml.transform.sax.SAXSource;
import javax.xml.xquery.XQItem;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.jdbc.JDBC2XMLMapper;
import com.artofarc.esb.jdbc.JDBCAttachments;
import com.artofarc.esb.jdbc.JDBCConnection;
import com.artofarc.esb.jdbc.JDBCParameter;
import com.artofarc.esb.jdbc.JDBCResult;
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
					JDBC2XMLMapper mapper = new JDBC2XMLMapper(_schemaSet, param.getXmlElement());
					SAXSource saxSource = new SAXSource(mapper.createParser(context, conn, (Struct) cs.getObject(param.getPos())), null);
					message.reset(BodyType.XQ_ITEM, context.getXQDataFactory().createItemFromDocument(saxSource, null));
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
					jdbcAttachments.parseAttachments(conn, struct, message);
				}
			} else {
				message.getVariables().put(param.getBindName(), cs.getObject(param.getPos()));
			}
		}
		return new JDBCResult(cs);
	}

}
