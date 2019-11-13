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
package com.artofarc.esb.http;

import java.util.List;
import java.util.ListIterator;

public final class HttpEndpoint {

	private final String _name;
	private final List<HttpUrl> _endpoints;
	private final String _basicAuthCredential;
	private final int _connectionTimeout;
	private final int _retries;
	private final Integer _checkAliveInterval;
	private final Integer _keepAliveInterval;
	private final long _modificationTime;

	public HttpEndpoint(String name, List<HttpUrl> endpoints, String username, String password, int connectionTimeout, int retries, Integer checkAliveInterval, Integer keepAliveInterval, long modificationTime) {
		if (name != null) {
			_name = name;
		} else {
			StringBuilder builder = new StringBuilder("\"");
			for (HttpUrl httpUrl : endpoints) {
				builder.append(httpUrl).append(',');
			}
			builder.setCharAt(builder.length() - 1, '"');
			_name = builder.toString();
		}
		_endpoints = endpoints;
		_basicAuthCredential = username != null && password != null ? username + ':' + password : null;
		_connectionTimeout = connectionTimeout;
		_retries = retries;
		_checkAliveInterval = checkAliveInterval;
		_keepAliveInterval = keepAliveInterval;
		_modificationTime = modificationTime;
	}

	public String getName() {
		return _name;
	}

	public List<HttpUrl> getHttpUrls() {
		return _endpoints;
	}

	public String getBasicAuthCredential() {
		return _basicAuthCredential;
	}

	public int getConnectionTimeout() {
		return _connectionTimeout;
	}

	public int getRetries() {
		return _retries;
	}

	public Integer getCheckAliveInterval() {
		return _checkAliveInterval;
	}

	public Integer getKeepAliveInterval() {
		return _keepAliveInterval;
	}

	public long getModificationTime() {
		return _modificationTime;
	}

	@Override
	public int hashCode() {
		return _name.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (!(obj instanceof HttpEndpoint)) return false;
		return _name.equals(((HttpEndpoint) obj)._name);
	}

	public boolean isCompatible(HttpEndpoint other) {
		ListIterator<HttpUrl> i1 = _endpoints.listIterator();
		ListIterator<HttpUrl> i2 = other._endpoints.listIterator();
		while (i1.hasNext() && i2.hasNext()) {
			HttpUrl o1 = i1.next();
			HttpUrl o2 = i2.next();
			if (!o1.isCompatible(o2)) return false;
		}
		return !(i1.hasNext() || i2.hasNext());
	}

	public boolean hasSameConfig(HttpEndpoint other) {
		return _endpoints.equals(other._endpoints) && _connectionTimeout == other._connectionTimeout && _retries == other._retries
				&& isEqual(_checkAliveInterval, other._checkAliveInterval) && isEqual(_keepAliveInterval, other._keepAliveInterval);
	}

	private static boolean isEqual(Integer i, Integer j) {
		return i == null && j == null || i != null && i.equals(j);
	}

}
