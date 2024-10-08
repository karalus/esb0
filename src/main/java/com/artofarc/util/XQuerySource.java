/*
 * Copyright 2021 Andre Karalus
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
package com.artofarc.util;

import java.util.Arrays;
import java.util.WeakHashMap;

import javax.xml.namespace.QName;
import javax.xml.xquery.*;

public final class XQuerySource {

	private static final ObjectPool<XQuerySource> POOL = new ObjectPool<>(new WeakHashMap<>(1024));

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

	public static XQuerySource create(byte[] data) {
		return POOL.intern(new XQuerySource(data));
	}

	public static XQuerySource create(String xquery) {
		return POOL.intern(new XQuerySource(xquery));
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

	private volatile ExternalVariables _externalVariables;

	/**
	 * Warning: In Saxon types belong to the XQDataSource (net.sf.saxon.Configuration) they were created with!
	 * This must only be called with an XQPreparedExpression owned by GlobalContext's XMLProcessorFactory instance.
	 */
	public ExternalVariables getExternalVariables(XQPreparedExpression preparedExpression) throws XQException {
		// double checked locking
		ExternalVariables externalVariables = _externalVariables;
		if (externalVariables == null) {
			synchronized (this) {
				externalVariables = _externalVariables;
				if (externalVariables == null) {
					externalVariables = _externalVariables = new ExternalVariables(preparedExpression);
				}
			}
		}
		return externalVariables;
	}

	public final static class ExternalVariables {
		private final QName[] _names;
		private final XQItemType[] _types;
		private final int[] _occurrences;

		private ExternalVariables(XQPreparedExpression preparedExpression) throws XQException {
			_names = preparedExpression.getAllExternalVariables();
			_types = new XQItemType[_names.length];
			_occurrences = new int[_names.length];
			for (int i = 0; i < _names.length; ++i) {
				XQSequenceType sequenceType = preparedExpression.getStaticVariableType(_names[i]);
				XQItemType itemType = sequenceType.getItemType();
				// Only store type when it is concrete enough
				if (itemType.getItemKind() != XQItemType.XQITEMKIND_ITEM) {
					_types[i] = itemType;
				}
				_occurrences[i] = sequenceType.getItemOccurrence();
			}
		}

		public int getLength() {
			return _names.length;
		}

		public QName getName(int i) {
			return _names[i];
		}

		public XQItemType getType(int i) {
			return _types[i];
		}

		public int getOccurrence(int i) {
			return _occurrences[i];
		}
	}

}
