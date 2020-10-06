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
package com.artofarc.esb.context;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.WeakHashMap;

import javax.xml.xquery.XQConnection;
import javax.xml.xquery.XQException;
import javax.xml.xquery.XQPreparedExpression;
import javax.xml.xquery.XQStaticContext;

public final class XQuerySource {

	private static final WeakHashMap<XQuerySource, XQuerySource> CACHE = new WeakHashMap<>(256);

	private final String _xquery;
	private final byte[] _data;
	private final int _hashCode;

	private XQuerySource(byte[] data) {
		if (data == null) throw new NullPointerException();
		_xquery = null;
		_data = data;
		_hashCode = Arrays.hashCode(_data);
	}

	private XQuerySource(String xquery) {
		_xquery = xquery;
		_data = null;
		_hashCode = xquery.hashCode();
	}

	private synchronized static XQuerySource intern(XQuerySource instance) {
		XQuerySource existing = CACHE.get(instance);
		if (existing != null) {
			return existing;
		} else {
			CACHE.put(instance, instance);
			return instance;
		}
	}

	public static XQuerySource create(byte[] data) {
		return intern(new XQuerySource(data));
	}

	public static XQuerySource create(String xquery) {
		return intern(new XQuerySource(xquery));
	}

	@Override
	public int hashCode() {
		return _hashCode;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (other instanceof XQuerySource) {
			XQuerySource o = (XQuerySource) other;
			if (_xquery != null) {
				return _xquery.equals(o._xquery);
			} else {
				return Arrays.equals(_data, o._data);
			}
		}
		return false;
	}

	@Override
	public String toString() {
		return _xquery != null ? _xquery : new String(_data);
	}

	public XQPreparedExpression prepareExpression(XQConnection conn, String baseURI) throws XQException {
		if (baseURI != null) {
			XQStaticContext staticContext = conn.getStaticContext();
			staticContext.setBaseURI(baseURI);
			if (_xquery != null) {
				return conn.prepareExpression(_xquery, staticContext);
			} else {
				return conn.prepareExpression(new ByteArrayInputStream(_data), staticContext);
			}
		}
		if (_xquery != null) {
			return conn.prepareExpression(_xquery);
		} else {
			return conn.prepareExpression(new ByteArrayInputStream(_data));
		}
	}

}
