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
package com.artofarc.esb.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Array;
import java.sql.Blob;
import java.sql.SQLException;
import java.sql.Struct;
import java.util.Iterator;

import javax.mail.MessagingException;
import javax.mail.internet.MimeBodyPart;

import org.xml.sax.SAXException;

import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.MimeHelper;
import com.artofarc.util.IOUtils;
import com.artofarc.util.XSOMHelper;
import com.sun.xml.xsom.XSComplexType;
import com.sun.xml.xsom.XSElementDecl;
import com.sun.xml.xsom.XSSchemaSet;

public final class JDBCAttachments {

	private final XSElementDecl _element;
	private final XSComplexType _complexType;

	public JDBCAttachments(XSSchemaSet schemaSet, String rootUri, String rootName) {
		_element = schemaSet.getElementDecl(rootUri, rootName);
		_complexType = _element.getType().asComplexType();
	}

	public void parseAttachments(JDBCConnection conn, Struct struct, ESBMessage message) throws SAXException, SQLException, MessagingException {
		XSOMHelper xsomHelper = new XSOMHelper(_complexType, _element);
		xsomHelper.checkComplexType(struct.getSQLTypeName());
		xsomHelper.matchElement(null, "attachments");
		Object[] attachments = struct.getAttributes();
		if (attachments.length == 1) {
			Array array = (Array) attachments[0];
			xsomHelper.checkComplexType(conn.getSQLTypeName(array));
			xsomHelper.matchElement(null, "attachment");
			if (!xsomHelper.isStartArray()) {
				throw new SAXException("Expected array in " + xsomHelper.getCurrentComplexType().getName());
			}
			for (Object object : (Object[]) array.getArray()) {
				Struct attachment = (Struct) object;
				xsomHelper.checkComplexType(attachment.getSQLTypeName());
				Object[] attributes = attachment.getAttributes();
				Blob content = (Blob) attributes[0];
				String cid = (String) attributes[1];
				String contentType = (String) attributes[2];
				String name = attributes.length > 3 ? (String) attributes[3] : null;
				message.addAttachment(cid, contentType, content.getBytes(1, (int) content.length()), name);
			}
		}
	}

	public Struct createAttachments(ESBMessage message, JDBCConnection connection) throws SAXException, SQLException, MessagingException, IOException {
		XSOMHelper xsomHelper = new XSOMHelper(_complexType, _element);
		xsomHelper.matchElement(null, "attachments");
		String sqlTypeNameAttachments = xsomHelper.getComplexType().getName();
		xsomHelper.matchElement(null, "attachment");
		if (!xsomHelper.isStartArray()) {
			throw new SAXException("Expected array in " + xsomHelper.getCurrentComplexType().getName());
		}
		String sqlTypeNameAttachment = xsomHelper.getComplexType().getName();
		int size = xsomHelper.getComplexType().getAttributeUses().size() + 1;
		Object[] elements = new Object[message.getAttachments().size()];
		int pos = 0;
		for (Iterator<String> iter = message.getAttachments().keySet().iterator(); iter.hasNext();) {
			String cid = iter.next();
			MimeBodyPart mimeBodyPart = message.getAttachments().get(cid);
			Object[] attributes = new Object[size];
			Blob blob = connection.createBlob();
			try (InputStream is = mimeBodyPart.getInputStream(); OutputStream os = blob.setBinaryStream(1)) {
				IOUtils.copy(is, os);
			}
			attributes[0] = blob;
			attributes[1] = cid;
			attributes[2] = mimeBodyPart.getContentType();
			if (size > 3) {
				attributes[3] = MimeHelper.getDispositionName(mimeBodyPart);
			}
			elements[pos++] = connection.getConnection().createStruct(sqlTypeNameAttachment, attributes);
			iter.remove();
		}
		return connection.getConnection().createStruct(_complexType.getName(), new Object[] { connection.createArray(sqlTypeNameAttachments, elements) });
	}

}
