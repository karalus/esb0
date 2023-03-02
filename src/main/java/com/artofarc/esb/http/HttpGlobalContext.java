/*
 * Copyright 2022 Andre Karalus
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

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

public final class HttpGlobalContext implements CookiePolicy {

	private final static CookiePolicy defaultCookiePolicy;

	static {
		String cookiePolicy = System.getProperty("esb0.http.defaultCookiePolicy");
		if (cookiePolicy != null) {
			try {
				Field field = CookiePolicy.class.getField(cookiePolicy);
				defaultCookiePolicy = (CookiePolicy) field.get(null);
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException(e);
			}
		} else {
			defaultCookiePolicy = null;
		}
	}

	private final ProxyAuthenticator _proxyAuthenticator = new ProxyAuthenticator();
	private final CookieManager _cookieManager;
	private final ConcurrentHashMap<URI, CookiePolicy> _policies;
	private final HashMap<String, SSLContext> _keyStores = new HashMap<>();

	public HttpGlobalContext() {
		Authenticator.setDefault(_proxyAuthenticator);
		CookieHandler cookieHandler = CookieHandler.getDefault();
		if (defaultCookiePolicy != null) {
			if (cookieHandler != null) {
				HttpEndpointRegistry.logger.warn("System-wide CookieHandler already set");
			}
			CookieHandler.setDefault(_cookieManager = new CookieManager(null, this));
			_policies = new ConcurrentHashMap<>();
		} else if (cookieHandler instanceof CookieManager) {
			_cookieManager = (CookieManager) cookieHandler;
			_policies = null;
		} else {
			_cookieManager = null;
			_policies = null;
		}
	}

	public CookieManager getCookieManager() {
		return _cookieManager;
	}

	public ProxyAuthenticator getProxyAuthenticator() {
		return _proxyAuthenticator;
	}

	@Override
	public boolean shouldAccept(URI uri, HttpCookie cookie) {
		return _policies.getOrDefault(uri, defaultCookiePolicy).shouldAccept(uri, cookie);
	}

	public void setCookiePolicy(HttpUrl httpUrl, CookiePolicy cookiePolicy) throws Exception {
		if (_policies != null) {
			URI uri = httpUrl.getURI();
			CookiePolicy oldPolicy = cookiePolicy != null ? _policies.put(uri, cookiePolicy) : _policies.remove(uri);
			if (oldPolicy != null && oldPolicy != cookiePolicy) {
				for (HttpCookie httpCookie : _cookieManager.getCookieStore().get(uri)) {
					if (cookiePolicy == null || !cookiePolicy.shouldAccept(uri, httpCookie)) {
						_cookieManager.getCookieStore().remove(uri, httpCookie);
					}
				}
			}
		}
	}

	public synchronized SSLContext getSSLContext(ClassLoader classLoader, String keyStore, char[] keyStorePassword) throws GeneralSecurityException, IOException {
		SSLContext sslContext = _keyStores.get(keyStore);
		if (sslContext == null) {
			KeyStore ks = KeyStore.getInstance("PKCS12");
			InputStream ras = classLoader.getResourceAsStream(keyStore);
			try (InputStream is = ras != null ? ras : new FileInputStream(keyStore)) {
				ks.load(is, keyStorePassword);
			}
			KeyManagerFactory keyFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			keyFactory.init(ks, keyStorePassword);
			sslContext = SSLContext.getInstance("TLS");
			sslContext.init(keyFactory.getKeyManagers(), null, null);
			_keyStores.put(keyStore, sslContext);
		}
		return sslContext;
	}

	public void close() {
		Authenticator.setDefault(null);
	}

}
