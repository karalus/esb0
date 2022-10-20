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

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.artofarc.esb.Registry;

public final class HttpEndpointRegistry {

	protected final static Logger logger = LoggerFactory.getLogger(HttpEndpointRegistry.class);

	private final Map<HttpEndpoint, HttpUrlSelector> _map = new WeakHashMap<>(256);
	private final Map<String, HttpUrlSelector> _treeMap = new TreeMap<>();
	private final Registry _registry;

	public HttpEndpointRegistry(Registry registry) {
		_registry = registry;
	}

	public synchronized Collection<Map.Entry<String, HttpUrlSelector>> getHttpEndpoints() {
		expungeStaleEntries();
		return _treeMap.entrySet();
	}

	public synchronized HttpEndpoint validate(HttpEndpoint httpEndpoint) {
		for (Map.Entry<HttpEndpoint, HttpUrlSelector> entry : _map.entrySet()) {
			if (entry.getKey().equals(httpEndpoint)) {
				if (httpEndpoint.isCompatible(entry.getKey())) {
					if (httpEndpoint.hasSameConfig(entry.getKey())) {
						return entry.getKey();
					}
				} else {
					logger.warn("Incompatible HttpEndpoint " + httpEndpoint.getName() + ". All services using it should be redeployed.");
				}
				return httpEndpoint;
			}
		}
		// precache
		_map.put(httpEndpoint, null);
		_treeMap.put(httpEndpoint.getName(), null);
		return httpEndpoint;
	}

	public synchronized HttpUrlSelector getHttpUrlSelector(HttpEndpoint httpEndpoint) {
		HttpUrlSelector state = _map.get(httpEndpoint);
		if (state != null) {
			HttpEndpoint oldHttpEndpoint = state.getHttpEndpoint();
			if (oldHttpEndpoint != httpEndpoint && !oldHttpEndpoint.isCompatible(httpEndpoint)) {
				logger.info("Removing state for HttpEndpoint " + httpEndpoint.getName() + ": " + oldHttpEndpoint.getHttpUrls());
				removeHttpUrlSelector(httpEndpoint.getName(), state);
				state = null;
			} else if (oldHttpEndpoint.getModificationTime() < httpEndpoint.getModificationTime()) {
				logger.info("Take non diversifying parameters from most recent version for HttpEndpoint " + httpEndpoint.getName());
				state.setHttpEndpoint(httpEndpoint);
				_map.put(httpEndpoint, state);
			}
		}
		if (state == null) {
			expungeStaleEntries();
			state = new HttpUrlSelector(httpEndpoint, _registry.getDefaultWorkerPool());
			_registry.registerMBean(state, ",group=HttpEndpointState,name=" + httpEndpoint.getName());
			_map.put(httpEndpoint, state);
			_treeMap.put(httpEndpoint.getName(), state);
		}
		return state;
	}

	private void expungeStaleEntries() {
		for (Iterator<Map.Entry<String, HttpUrlSelector>> iter = _treeMap.entrySet().iterator(); iter.hasNext();) {
			Map.Entry<String, HttpUrlSelector> entry = iter.next();
			if (entry.getValue() != null && entry.getValue().getHttpEndpoint() == null) {
				removeHttpUrlSelector(entry.getKey(), entry.getValue());
				iter.remove();
			}
		}
	}

	private void removeHttpUrlSelector(String name, HttpUrlSelector state) {
		state.stop();
		_registry.unregisterMBean(",group=HttpEndpointState,name=" + name);
	}

	public synchronized void evictHttpUrlSelector(HttpEndpoint httpEndpoint) {
		if (httpEndpoint != null) {
			_map.remove(httpEndpoint);
			removeHttpUrlSelector(httpEndpoint.getName(), _treeMap.remove(httpEndpoint.getName()));
		} else {
			expungeStaleEntries();
		}
	}

	public synchronized void close() {
		for (Map.Entry<String, HttpUrlSelector> entry : _treeMap.entrySet()) {
			if (entry.getValue() != null) {
				removeHttpUrlSelector(entry.getKey(), entry.getValue());
			}
		}
	}

}
