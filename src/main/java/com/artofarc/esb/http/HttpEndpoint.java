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
package com.artofarc.esb.http;

import java.net.Proxy;
import java.net.http.HttpClient;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;

import javax.net.ssl.SSLContext;

public final class HttpEndpoint {

	private final String _name;
	private final List<HttpUrl> _endpoints;
	private final boolean _multiThreaded;
	private final String _basicAuthCredential;
	private final int _connectTimeout;
	private final int _retries;
	private final Integer _checkAliveInterval;
	private final HttpCheckAlive _checkAlive;
	private final long _modificationTime;
	private final Proxy _proxy;
	private final SSLContext _sslContext;
	private final HttpClient.Version _version;

	public HttpEndpoint(String name, List<HttpUrl> endpoints, boolean multiThreaded, String username, String password, int connectTimeout, int retries, Integer checkAliveInterval, HttpCheckAlive checkAlive, long modificationTime, Proxy proxy, SSLContext sslContext, HttpClient.Version version) {
		if (name != null) {
			_name = name;
		} else {
			StringBuilder builder = new StringBuilder("\"");
			for (HttpUrl httpUrl : endpoints) {
				builder.append(httpUrl.getBaseUrl()).append(',');
			}
			builder.setCharAt(builder.length() - 1, '"');
			_name = builder.toString();
		}
		_endpoints = endpoints;
		_multiThreaded = multiThreaded;
		_basicAuthCredential = username != null && password != null ? username + ':' + password : null;
		_connectTimeout = connectTimeout;
		_retries = retries;
		_checkAliveInterval = checkAliveInterval;
		_checkAlive = checkAlive;
		_modificationTime = modificationTime;
		_proxy = proxy;
		if (sslContext != null) {
			for (HttpUrl httpUrl : endpoints) {
				if (!"https".equals(httpUrl.getUrl().getProtocol())) {
					throw new IllegalArgumentException("All endpoints must use https protocol in " + _name);
				}
			}
		}
		_sslContext = sslContext;
		_version = version;
	}

	public String getName() {
		return _name;
	}

	public List<HttpUrl> getHttpUrls() {
		return _endpoints;
	}

	public boolean isMultiThreaded() {
		return _multiThreaded;
	}

	public String getBasicAuthCredential() {
		return _basicAuthCredential;
	}

	public int getConnectTimeout() {
		return _connectTimeout;
	}

	public int getRetries() {
		return _retries;
	}

	public Integer getCheckAliveInterval() {
		return _checkAliveInterval;
	}

	public HttpCheckAlive getHttpCheckAlive() {
		return _checkAlive;
	}

	public long getModificationTime() {
		return _modificationTime;
	}

	public Proxy getProxy() {
		return _proxy;
	}

	public SSLContext getSSLContext() {
		return _sslContext;
	}

	public HttpClient.Version getVersion() {
		return _version;
	}

	public int getMaxConnectTimeout() {
		return (_retries + 1) * _connectTimeout;
	}

	boolean isCompatible(HttpEndpoint other) {
		ListIterator<HttpUrl> i1 = _endpoints.listIterator();
		ListIterator<HttpUrl> i2 = other._endpoints.listIterator();
		while (i1.hasNext() && i2.hasNext()) {
			HttpUrl o1 = i1.next();
			HttpUrl o2 = i2.next();
			if (!o1.isCompatible(o2)) return false;
		}
		return !(i1.hasNext() || i2.hasNext());
	}

	boolean hasSameConfig(HttpEndpoint other) {
		return _endpoints.equals(other._endpoints) && _connectTimeout == other._connectTimeout && _retries == other._retries && _multiThreaded == other._multiThreaded
				&& _version == other._version && Objects.equals(_checkAliveInterval, other._checkAliveInterval) && Objects.equals(_checkAlive, other._checkAlive)
				&& Objects.equals(_basicAuthCredential, other._basicAuthCredential) && Objects.equals(_proxy, other._proxy) && Objects.equals(_sslContext, other._sslContext);
	}

}
