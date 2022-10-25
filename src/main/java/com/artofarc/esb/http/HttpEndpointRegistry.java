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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.artofarc.esb.Registry;

public final class HttpEndpointRegistry {

	protected final static Logger logger = LoggerFactory.getLogger(HttpEndpointRegistry.class);

	private final ArrayList<WeakReference<HttpEndpoint>> _httpEndpoints = new ArrayList<>(256);
	private final Map<String, HttpUrlSelector> _map = new HashMap<>(256);
	private final Registry _registry;

	public HttpEndpointRegistry(Registry registry) {
		_registry = registry;
	}

	public Collection<Map.Entry<String, HttpUrlSelector>> getHttpEndpoints() {
		Map<String, HttpUrlSelector> treeMap = new TreeMap<>();
		synchronized (_httpEndpoints) {
			for (Iterator<WeakReference<HttpEndpoint>> iter = _httpEndpoints.iterator(); iter.hasNext();) {
				HttpEndpoint httpEndpoint = iter.next().get();
				if (httpEndpoint != null) {
					treeMap.put(httpEndpoint.getName(), null);
				} else {
					iter.remove();
				}
			}
		}
		synchronized (this) {
			expungeStaleEntries();
			treeMap.putAll(_map);
		}
		return treeMap.entrySet();
	}

	public HttpEndpoint validate(HttpEndpoint httpEndpoint) {
		synchronized (_httpEndpoints) {
			for (Iterator<WeakReference<HttpEndpoint>> iter = _httpEndpoints.iterator(); iter.hasNext();) {
				HttpEndpoint pivot = iter.next().get();
				if (pivot != null) {
					if (httpEndpoint.getName().equals(pivot.getName()) && httpEndpoint.hasSameConfig(pivot)) {
						return pivot;
					}
				} else {
					iter.remove();
				}
			}
			_httpEndpoints.add(new WeakReference<>(httpEndpoint));
		}
		return httpEndpoint;
	}

	public synchronized HttpUrlSelector getHttpUrlSelector(HttpEndpoint httpEndpoint) {
		HttpUrlSelector state = _map.get(httpEndpoint.getName());
		if (state != null && state.missesHttpEndpoint(httpEndpoint)) {
			HttpEndpoint pivot = state.getHttpEndpoint();
			if (pivot == null || pivot.isCompatible(httpEndpoint)) {
				state.addHttpEndpoint(httpEndpoint);
			} else {
				logger.warn("Incompatible HttpEndpoint " + httpEndpoint.getName() + ". All services using it should be redeployed.");
				logger.info("Removing state for HttpEndpoint " + httpEndpoint.getName() + ": " + pivot.getHttpUrls());
				removeHttpUrlSelector(httpEndpoint.getName(), state);
				state = null;
			}
		}
		if (state == null) {
			expungeStaleEntries();
			state = new HttpUrlSelector(httpEndpoint, _registry.getDefaultWorkerPool());
			_registry.registerMBean(state, ",group=HttpEndpointState,name=" + httpEndpoint.getName());
			_map.put(httpEndpoint.getName(), state);
		}
		return state;
	}

	private void expungeStaleEntries() {
		for (Iterator<Map.Entry<String, HttpUrlSelector>> iter = _map.entrySet().iterator(); iter.hasNext();) {
			Map.Entry<String, HttpUrlSelector> entry = iter.next();
			if (entry.getValue().getHttpEndpoint() == null) {
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
			removeHttpUrlSelector(httpEndpoint.getName(), _map.remove(httpEndpoint.getName()));
		} else {
			expungeStaleEntries();
		}
	}

	public synchronized void close() {
		for (Map.Entry<String, HttpUrlSelector> entry : _map.entrySet()) {
			removeHttpUrlSelector(entry.getKey(), entry.getValue());
		}
	}

}
