package com.artofarc.esb.jdbc;

import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.sql.Types;

import javax.xml.datatype.XMLGregorianCalendar;

public final class JDBCParameter {

	private final int pos;
	private final String typeName;
	private final int type;
	private final boolean body;
	private final String bindName;
	private final Integer truncate;

	public JDBCParameter(int pos, String typeName, boolean body, String bindName, Integer truncate) throws NoSuchFieldException {
		this.pos = pos;
		this.typeName = typeName;
		this.type = convertSQLTypeName(typeName);
		this.body = body;
		this.bindName = bindName;
		this.truncate = truncate;
	}
	
	public final static int convertSQLTypeName(String typeName) throws NoSuchFieldException {
		Field field = Types.class.getField(typeName);
		try {
			return field.getInt(null);
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}
	
	public int getPos() {
		return pos;
	}

	public String getTypeName() {
		return typeName;
	}
	
	public int getType() {
		return type;
	}

	public boolean isBody() {
		return body;
	}

	public String getBindName() {
		return bindName;
	}
	
	public Integer getTruncate() {
		return truncate;
	}

	@SuppressWarnings("unchecked")
	public <T extends Object> T alignValue(Object value) {
		switch (type) {
		case Types.TIMESTAMP:
			if (value instanceof XMLGregorianCalendar) {
				XMLGregorianCalendar calendar = (XMLGregorianCalendar) value;
				return (T) new Timestamp(calendar.toGregorianCalendar().getTimeInMillis());
			}
			if (value instanceof Long) {
				return (T) new Timestamp((Long) value);
			}
			break;
		case Types.VARCHAR:
			if (truncate != null && value != null) {
				String s = (String) value;
				return (T) (s.length() > truncate ? s.substring(0, truncate) : s);
			}
		default:
			break;
		}
		return (T) value;
	}

}
