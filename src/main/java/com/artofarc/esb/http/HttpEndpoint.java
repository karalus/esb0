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

public final class HttpEndpoint {

	private final String _name;
	private final List<HttpUrl> _endpoints;
	private final int _connectionTimeout;
	private final int _retries;
	private final Integer _checkAliveInterval;
	private final Integer _keepAliveInterval;
	private final long _modificationTime;

	public HttpEndpoint(String name, List<HttpUrl> endpoints, int connectionTimeout, int retries, Integer checkAliveInterval, Integer keepAliveInterval, long modificationTime) {
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
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		HttpEndpoint other = (HttpEndpoint) obj;
		return _name.equals(other._name);
	}

	public boolean isCompatible(HttpEndpoint other) {
		return _endpoints.equals(other._endpoints);
	}

	public boolean hasSameConfig(HttpEndpoint other) {
		return _connectionTimeout == other._connectionTimeout && _retries == other._retries && isEqual(_checkAliveInterval, other._checkAliveInterval) && isEqual(_keepAliveInterval, other._keepAliveInterval);
	}

	private static boolean isEqual(Integer i, Integer j) {
		return i == null && j == null || i != null && i.equals(j);
	}

}
