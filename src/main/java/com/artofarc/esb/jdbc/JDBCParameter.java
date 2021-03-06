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

import java.lang.reflect.Field;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.namespace.QName;
import javax.xml.transform.dom.DOMResult;

import org.w3c.dom.Node;

import com.artofarc.util.Collections;

public final class JDBCParameter {

	final static Map<String, Integer> TYPES = new HashMap<>();
	final static Map<Integer, String> CODES;
	final static TimeZone TIME_ZONE;

	static{
		for (Field field : Types.class.getFields()) {
			try {
				TYPES.put(field.getName(), field.getInt(null));
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			}
		}
		CODES = Collections.inverseMap(TYPES.entrySet(), true);
		String timezone = System.getProperty("esb0.jdbc.mapper.timezone");
		TIME_ZONE = timezone != null ? TimeZone.getTimeZone(timezone) : TimeZone.getDefault();
	}

	static Calendar convert(Date date) {
		Calendar calendar = new GregorianCalendar(TIME_ZONE);
		calendar.setTime(date);
		return calendar;
	}

	private final int _pos;
	private final String _typeName;
	private final int _type;
	private final boolean _body;
	private final boolean _attachments;
	private final String _bindName;
	private final Integer _truncate;
	private final QName _xmlElement;

	public JDBCParameter(int pos, String typeName, boolean body, boolean attachments, String bindName, Integer truncate, String xmlElement) {
		Integer code = TYPES.get(typeName);
		if (code == null) {
			throw new IllegalArgumentException("Not a SQL type: " + typeName);
		}
		_pos = pos;
		_typeName = typeName;
		_type = code;
		_body = body;
		_attachments = attachments;
		_bindName = bindName;
		_truncate = truncate;
		_xmlElement = xmlElement != null ? QName.valueOf(xmlElement) : null;
	}

	public int getPos() {
		return _pos;
	}

	public String getTypeName() {
		return _typeName;
	}

	public int getType() {
		return _type;
	}

	public boolean isBody() {
		return _body;
	}

	public boolean isAttachments() {
		return _attachments;
	}

	public String getBindName() {
		return _bindName;
	}

	public Integer getTruncate() {
		return _truncate;
	}

	public QName getXmlElement() {
		return _xmlElement;
	}

	public Object alignValue(Object value, JDBCConnection conn) throws SQLException {
		switch (_type) {
		case Types.TIMESTAMP:
			if (value instanceof XMLGregorianCalendar) {
				XMLGregorianCalendar calendar = (XMLGregorianCalendar) value;
				return new Timestamp(calendar.toGregorianCalendar(TIME_ZONE, null, null).getTimeInMillis());
			}
			if (value instanceof Calendar) {
				Calendar calendar = (Calendar) value;
				return new Timestamp(calendar.getTimeInMillis());
			}
			if (value instanceof Long) {
				return new Timestamp((Long) value);
			}
			break;
		case Types.CHAR:
		case Types.VARCHAR:
			if (_truncate != null) {
				return truncate((String) value);
			}
			break;
		case Types.CLOB:
			Clob clob = conn.createClob();
			clob.setString(1, (String) value);
			return clob;
		case Types.BLOB:
			Blob blob = conn.createBlob();
			blob.setBytes(1, (byte[]) value);
			return blob;
		case Types.SQLXML:
			SQLXML sqlxml = conn.createSQLXML();
			sqlxml.setResult(DOMResult.class).setNode((Node) value);
			return sqlxml;
		default:
			break;
		}
		return value;
	}

	public String truncate(String s) {
		return s.length() > _truncate ? s.substring(0, _truncate) : s;
	}

}
