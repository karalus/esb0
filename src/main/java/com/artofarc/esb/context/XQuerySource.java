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

import javax.xml.xquery.XQConnection;
import javax.xml.xquery.XQException;
import javax.xml.xquery.XQPreparedExpression;

import com.artofarc.esb.resource.XQDataSourceFactory;

public final class XQuerySource {

	private final String _xquery;
	private final byte[] _data;
	private final int _hashCode;

	public XQuerySource(byte[] data) {
		_xquery = null;
		_data = data;
		_hashCode = Arrays.hashCode(_data);
	}

	public XQuerySource(String xquery) {
		_xquery = xquery;
		_data = null;
		_hashCode = xquery.hashCode();
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
				if (o._xquery != null) {
					return _xquery.equals(o._xquery);
				}
				return false;
			}
			if (o._data != null) {
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
		if (_xquery != null) {
			if (baseURI != null) {
				return conn.prepareExpression(_xquery, XQDataSourceFactory.getStaticContext(conn, baseURI));
			} else {
				return conn.prepareExpression(_xquery);
			}
		}
		if (baseURI != null) {
			return conn.prepareExpression(new ByteArrayInputStream(_data), XQDataSourceFactory.getStaticContext(conn, baseURI));
		} else {
			return conn.prepareExpression(new ByteArrayInputStream(_data));
		}
	}

}