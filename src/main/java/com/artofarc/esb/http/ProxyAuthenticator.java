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

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URL;
import java.net.URLDecoder;
import java.util.concurrent.ConcurrentHashMap;

public class ProxyAuthenticator extends Authenticator {

	private final ConcurrentHashMap<Proxy, PasswordAuthentication> _map = new ConcurrentHashMap<>();

	public final Proxy registerProxy(String proxyUrl) throws IOException {
		if (proxyUrl != null) {
			URL url = new URL(proxyUrl);
			Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(url.getHost(), url.getPort()));
			PasswordAuthentication passwordAuthentication = null;
			if (url.getUserInfo() != null) {
				int i = url.getUserInfo().indexOf(':');
				String user = URLDecoder.decode(url.getUserInfo().substring(0, i), "UTF-8");
				String password = URLDecoder.decode(url.getUserInfo().substring(i + 1), "UTF-8");
				passwordAuthentication = new PasswordAuthentication(user, password.toCharArray());
			}
			_map.putIfAbsent(proxy, passwordAuthentication);
			return proxy;
		}
		return Proxy.NO_PROXY;
	}

	@Override
	protected PasswordAuthentication getPasswordAuthentication() {
		if (getRequestorType() == RequestorType.PROXY) {
			Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(getRequestingHost(), getRequestingPort()));
			PasswordAuthentication passwordAuthentication = _map.get(proxy);
			return new PasswordAuthentication(passwordAuthentication.getUserName(), passwordAuthentication.getPassword());
		}
		return null;
	}

}
