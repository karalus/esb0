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

import java.net.URL;

public final class HttpUrl {

	private final URL _url;
	private final int _weight;
	private final boolean _active;
	private final String _host;
	private final int _port;
	private final String _path;

	public HttpUrl(String url, int weight, boolean active) throws java.net.MalformedURLException {
		_url = new URL(url);
		_host = _url.getHost();
		_port = _url.getPort() < 0 ? _url.getDefaultPort() : _url.getPort();
		_path = _url.getPath();
		_weight = weight;
		_active = active;
	}

	public URL getUrl() {
		return _url;
	}

	public int getWeight() {
		return _weight;
	}

	public boolean isActive() {
		return _active;
	}

	public boolean isCompatible(HttpUrl other) {
		return _port == other._port && _host.equals(other._host);
	}

	@Override
	public int hashCode() {
		return _host.hashCode() + _port ^ _path.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!(obj instanceof HttpUrl))
			return false;
		HttpUrl other = (HttpUrl) obj;
		return _port == other._port && _host.equals(other._host) && _path.equals(other._path);
	}

	@Override
	public String toString() {
		return "http://" + _host + ':' + _port;
	}

}
