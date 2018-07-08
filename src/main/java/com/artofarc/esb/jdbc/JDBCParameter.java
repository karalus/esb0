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
import java.sql.Timestamp;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

import javax.xml.datatype.XMLGregorianCalendar;

import com.artofarc.util.Collections;

public final class JDBCParameter {

	public final static Map<String, Integer> TYPES = new HashMap<>();
	public final static Map<Integer, String> CODES;
	
	static{
		for (Field field : Types.class.getFields()) {
			try {
				TYPES.put(field.getName(), field.getInt(null));
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			}
		}
		CODES = Collections.inverseMap(TYPES);
	}
	
	private final int pos;
	private final String typeName;
	private final int type;
	private final boolean body;
	private final String bindName;
	private final Integer truncate;

	public JDBCParameter(int pos, String typeName, boolean body, String bindName, Integer truncate) throws NoSuchFieldException {
		Integer code = TYPES.get(typeName);
		if (code == null) {
			throw new IllegalArgumentException("Not a SQL type: " + typeName);
		}
		this.pos = pos;
		this.typeName = typeName;
		this.type = code;
		this.body = body;
		this.bindName = bindName;
		this.truncate = truncate;
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
		case Types.CHAR:
		case Types.VARCHAR:
		case Types.CLOB:
			if (truncate != null && value != null) {
				String s = (String) value;
				return (T) (s.length() > truncate ? s.substring(0, truncate) : s);
			}
			break;
		case Types.BLOB:
			final byte[] msgBody = (byte[]) value;
			if (truncate != null && msgBody.length > truncate) {
				final byte[] newMsgBody = new byte[truncate]; 
				System.arraycopy(msgBody, 0, newMsgBody, 0, truncate);
				return (T) newMsgBody;
			}
			break;
		default:
			break;
		}
		return (T) value;
	}

}
