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

import java.net.MalformedURLException;
import java.net.URL;

public final class HttpUrl {
	
	private final URL _url;
	private final int weight;
	private final boolean active;
	
	private final String host;
	private final int port;
	
	public HttpUrl(String url, int weight, boolean active) throws MalformedURLException {
		_url = new URL(url);
		host = _url.getHost();
		port = _url.getPort() < 0 ? _url.getDefaultPort() : _url.getPort();
		this.weight = weight;
		this.active = active;
	}

	public URL getUrl() {
		return _url;
	}

	public int getWeight() {
		return weight;
	}

	public boolean isActive() {
		return active;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((host == null) ? 0 : host.hashCode());
		result = prime * result + port;
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		HttpUrl other = (HttpUrl) obj;
		if (host == null) {
			if (other.host != null)
				return false;
		} else if (!host.equals(other.host))
			return false;
		return port == other.port;
	}

}
