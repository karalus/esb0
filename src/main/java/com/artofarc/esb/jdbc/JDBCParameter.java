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
package com.artofarc.esb.jdbc;

import java.io.InputStream;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.namespace.QName;
import javax.xml.transform.dom.DOMResult;

import org.w3c.dom.Node;

public final class JDBCParameter {

	private final static TimeZone TIME_ZONE;

	static {
		String timezone = System.getProperty("esb0.jdbc.mapper.timezone");
		TIME_ZONE = timezone != null ? TimeZone.getTimeZone(timezone) : TimeZone.getDefault();
	}

	static Calendar getCalendarInstance() {
		return new GregorianCalendar(TIME_ZONE);// Faster than Calendar.getInstance(TIME_ZONE);
	}

	private final int _pos;
	private final JDBCType _type;
	private final boolean _body;
	private final boolean _attachments;
	private final String _bindName;
	private final Integer _truncate;
	private final QName _xmlElement;

	public JDBCParameter(int pos, String typeName, boolean body, boolean attachments, String bindName, Integer truncate, String xmlElement) {
		_pos = pos;
		_type = JDBCType.valueOf(typeName);
		_body = body;
		_attachments = attachments;
		_bindName = bindName;
		_truncate = truncate;
		_xmlElement = xmlElement != null ? QName.valueOf(xmlElement) : null;
	}

	public int getPos() {
		return _pos;
	}

	public JDBCType getType() {
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

	public void setParameter(PreparedStatement ps, Object value, JDBCConnection conn) throws SQLException {
		if (value != null) {
			Calendar calendar = null;
			switch (_type) {
			case TIMESTAMP_WITH_TIMEZONE:
				calendar = Calendar.getInstance(TIME_ZONE);
				// no break
			case DATE:
			case TIMESTAMP:
				if (value instanceof XMLGregorianCalendar) {
					calendar = ((XMLGregorianCalendar) value).toGregorianCalendar();
					value = new Timestamp(calendar.getTimeInMillis());
				} else if (value instanceof Calendar) {
					calendar = (Calendar) value;
					value = new Timestamp(calendar.getTimeInMillis());
				} else if (value instanceof Long) {
					value = new Timestamp((Long) value);
				}
				if (value instanceof Timestamp) {
					ps.setTimestamp(_pos, (Timestamp) value, calendar);
				} else if (value instanceof Date) {
					ps.setDate(_pos, (Date) value, calendar);
				} else {
					throw new IllegalArgumentException("Not a date/timestamp type " + value.getClass().getName());
				}
				break;
			case CHAR:
			case VARCHAR:
				ps.setString(_pos, truncate((String) value));
				break;
			case CLOB:
				Clob clob = conn.createClob();
				clob.setString(1, (String) value);
				ps.setClob(_pos, clob);
				break;
			case BLOB:
				if (value instanceof InputStream) {
					ps.setBinaryStream(_pos, (InputStream) value);
				} else {
					Blob blob = conn.createBlob();
					blob.setBytes(1, (byte[]) value);
					ps.setBlob(_pos, blob);
				}
				break;
			case SQLXML:
				SQLXML sqlxml = conn.createSQLXML();
				sqlxml.setResult(DOMResult.class).setNode((Node) value);
				ps.setSQLXML(_pos, sqlxml);
				break;
			default:
				ps.setObject(_pos, value, _type.getVendorTypeNumber());
				break;
			}
		} else {
			ps.setNull(_pos, _type.getVendorTypeNumber());
		}
	}

	private String truncate(String s) {
		return _truncate != null && s.length() > _truncate ? s.substring(0, _truncate) : s;
	}

}
