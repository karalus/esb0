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
import java.util.concurrent.ConcurrentHashMap;

import com.artofarc.util.URLUtils;

public class ProxyAuthenticator extends Authenticator {

	private final ConcurrentHashMap<Proxy, PasswordAuthentication> _map = new ConcurrentHashMap<>();
	private final Authenticator _authenticator;

	ProxyAuthenticator(Authenticator authenticator) {
		_authenticator = authenticator;
	}

	public final Proxy registerProxy(String proxyUrl) throws IOException {
		URL url = new URL(proxyUrl);
		Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(url.getHost(), url.getPort()));
		if (url.getUserInfo() != null) {
			int i = url.getUserInfo().indexOf(':');
			String user = URLUtils.decode(url.getUserInfo().substring(0, i));
			String password = URLUtils.decode(url.getUserInfo().substring(i + 1));
			PasswordAuthentication old = _map.put(proxy, new PasswordAuthentication(user, password.toCharArray()));
			if (old != null && !old.getUserName().equals(user)) {
				HttpEndpointRegistry.logger.warn("Proxy user has changed for " + proxy);
			}
		}
		return proxy;
	}

	@Override
	protected PasswordAuthentication getPasswordAuthentication() {
		if (getRequestorType() == RequestorType.PROXY) {
			Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(getRequestingHost(), getRequestingPort()));
			PasswordAuthentication passwordAuthentication = _map.get(proxy);
			if (passwordAuthentication != null) {
				return new PasswordAuthentication(passwordAuthentication.getUserName(), passwordAuthentication.getPassword());
			}
		}
		if (_authenticator != null) {
			// In Java 11 we can use Authenticator::requestPasswordAuthenticationInstance
			setDefault(_authenticator);
			try {
				return requestPasswordAuthentication(getRequestingHost(), getRequestingSite(), getRequestingPort(), getRequestingProtocol(), getRequestingPrompt(), getRequestingScheme(),
						getRequestingURL(), getRequestorType());
			} finally {
				setDefault(this);
			}
		}
		return null;
	}

}
