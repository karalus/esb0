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

import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.artofarc.esb.context.GlobalContext;

public final class HttpEndpointRegistry {

	protected final static Logger logger = LoggerFactory.getLogger(HttpEndpointRegistry.class);

	private final HashMap<HttpEndpoint, HttpUrlSelector> _map = new HashMap<>();
	private final GlobalContext _globalContext;

	public HttpEndpointRegistry(GlobalContext globalContext) {
		_globalContext = globalContext;
	}

	public Set<Entry<HttpEndpoint, HttpUrlSelector>> getHttpEndpoints() {
		return _map.entrySet();
	}

	public synchronized HttpUrlSelector getHttpUrlSelector(HttpEndpoint httpEndpoint) {
		HttpUrlSelector state = _map.get(httpEndpoint);
		if (state != null && !state.getHttpEndpoint().getHttpUrls().equals(httpEndpoint.getHttpUrls())) {
			// not compatible
			logger.info("Removing state for HttpEndpoint " + httpEndpoint.getName() + ": " + state.getHttpEndpoint().getHttpUrls());
			removeHttpUrlSelector(httpEndpoint, state);
			_map.remove(httpEndpoint);
			state = null;
		}
		if (state == null) {
			state = new HttpUrlSelector(httpEndpoint, _globalContext.getDefaultWorkerPool());
			_map.put(httpEndpoint, state);
			_globalContext.registerMBean(state, ",group=HttpEndpointState,name=" + httpEndpoint.getName());
		} else {
			// take non diversifying parameters from most recent version
			if (state.getHttpEndpoint().getModificationTime() < httpEndpoint.getModificationTime()) {
				state.setHttpEndpoint(httpEndpoint);
			}
		}
		return state;
	}

	private void removeHttpUrlSelector(HttpEndpoint httpEndpoint, HttpUrlSelector state) {
		state.stop();
		_globalContext.unregisterMBean(",group=HttpEndpointState,name=" + httpEndpoint.getName());
	}

	public synchronized void close() {
		for (Entry<HttpEndpoint, HttpUrlSelector> entry : _map.entrySet()) {
			removeHttpUrlSelector(entry.getKey(), entry.getValue());
		}
	}

}
