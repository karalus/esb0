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
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.artofarc.esb.Registry;
import com.artofarc.util.DataStructures;

public final class HttpEndpointRegistry {

	protected final static Logger logger = LoggerFactory.getLogger(HttpEndpointRegistry.class);

	private final ArrayList<WeakReference<HttpEndpoint>> _httpEndpoints = new ArrayList<>(256);
	private final Map<String, HttpUrlSelector> _map = new ConcurrentHashMap<>(256);
	private final Registry _registry;

	public HttpEndpointRegistry(Registry registry) {
		_registry = registry;
	}

	public Map<String, HttpEndpoint> getHttpEndpoints() {
		synchronized (_httpEndpoints) {
			Map<String, HttpEndpoint> map = DataStructures.createHashMap(_httpEndpoints.size());
			for (Iterator<WeakReference<HttpEndpoint>> iter = _httpEndpoints.iterator(); iter.hasNext();) {
				HttpEndpoint httpEndpoint = iter.next().get();
				if (httpEndpoint != null) {
					map.put(httpEndpoint.getName(), httpEndpoint);
				} else {
					iter.remove();
				}
			}
			return map;
		}
	}

	public Map<String, HttpUrlSelector> getHttpUrlSelectors() {
		Map<String, HttpUrlSelector> treeMap = new TreeMap<>();
		for (String httpEndpointName : getHttpEndpoints().keySet()) {
			treeMap.put(httpEndpointName, null);
		}
		expungeStaleEntries();
		treeMap.putAll(_map);
		return treeMap;
	}

	public HttpEndpoint validate(HttpEndpoint httpEndpoint) {
		synchronized (_httpEndpoints) {
			HttpEndpoint pivot = getHttpEndpoints().get(httpEndpoint.getName());
			if (pivot != null && httpEndpoint.hasSameConfig(pivot)) {
				return pivot;
			}
			_httpEndpoints.add(new WeakReference<>(httpEndpoint));
		}
		return httpEndpoint;
	}

	public HttpUrlSelector getHttpUrlSelector(HttpEndpoint httpEndpoint) {
		HttpUrlSelector httpUrlSelector = _map.get(httpEndpoint.getName());
		if (httpUrlSelector == null || httpUrlSelector.missesHttpEndpoint(httpEndpoint)) {
			expungeStaleEntries();
			httpUrlSelector = _map.compute(httpEndpoint.getName(), (name, state) -> {
				if (state != null && state.missesHttpEndpoint(httpEndpoint)) {
					HttpEndpoint pivot = state.getHttpEndpoint();
					if (pivot == null || pivot.isCompatible(httpEndpoint)) {
						state.addHttpEndpoint(httpEndpoint);
					} else {
						logger.warn("Incompatible HttpEndpoint " + name + ". All services using it should be redeployed.");
						logger.info("Removing state for HttpEndpoint " + name + ": " + pivot.getHttpUrls());
						removeHttpUrlSelector(name, state);
						state = null;
					}
				}
				if (state == null) {
					state = new HttpUrlSelector(httpEndpoint, _registry.getDefaultWorkerPool());
					_registry.getHttpGlobalContext().registerProxy(httpEndpoint);
					_registry.registerMBean(state, ",group=HttpEndpointState,name=" + name);
				}
				return state;
			});
		}
		return httpUrlSelector;
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

	public void evictHttpUrlSelector(HttpEndpoint httpEndpoint) {
		if (httpEndpoint != null) {
			removeHttpUrlSelector(httpEndpoint.getName(), _map.remove(httpEndpoint.getName()));
		} else {
			expungeStaleEntries();
		}
	}

	public void close() {
		for (Map.Entry<String, HttpUrlSelector> entry : _map.entrySet()) {
			removeHttpUrlSelector(entry.getKey(), entry.getValue());
		}
	}

}
