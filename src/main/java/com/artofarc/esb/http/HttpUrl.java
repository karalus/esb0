/*
 * Copyright 2023 Andre Karalus
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

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

public final class HttpUrl {

	private final String _urlStr;
	private final URL _url;
	private final String _protocol;
	private final String _host;
	private final int _port;
	private final String _path;
	private final int _weight;
	private final boolean _active;

	public HttpUrl(String url, int weight, boolean active) throws MalformedURLException {
		_url = new URL(_urlStr = url);
		_protocol = _url.getProtocol().intern();
		_host = _url.getHost().intern();
		_port = _url.getPort() < 0 ? _url.getDefaultPort() : _url.getPort();
		_path = _url.getPath();
		_weight = weight;
		_active = active;
	}

	public String getBaseUrl() {
		String s = _protocol + "://" + _host;
		return _port == _url.getDefaultPort() ? s : s + ':' + _port;
	}

	public String getUrlStr() {
		return _urlStr;
	}

	public URL getURL() {
		return _url;
	}

	URI getURI() throws URISyntaxException {
		return _url.toURI();
	}

	public int getWeight() {
		return _weight;
	}

	public boolean isActive() {
		return _active;
	}

	public boolean isCompatible(HttpUrl other) {
		return _host == other._host && _port == other._port && _protocol == other._protocol;
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
		return _host == other._host && _port == other._port && _protocol == other._protocol && _path.equals(other._path) && _weight == other._weight && _active == other._active;
	}

	@Override
	public String toString() {
		return getBaseUrl();
	}

}
