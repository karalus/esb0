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
package com.artofarc.esb.resource;

import java.util.HashMap;
import java.util.Map;

public abstract class ResourceFactory<R extends AutoCloseable, D, P> implements AutoCloseable {

	private final Map<D, R> _pool = new HashMap<>();

	abstract protected R createResource(D descriptor, P param) throws Exception;

	public synchronized R getResource(D descriptor, P param) throws Exception {
		R resource = _pool.get(descriptor);
		if (resource == null) {
			resource = createResource(descriptor, param);
			_pool.put(descriptor, resource);
		}
		return resource;
	}

	public final R getResource(D descriptor) throws Exception {
		return getResource(descriptor, null);
	}

	public final void close(D descriptor) throws Exception {
		R resource;
		synchronized (this) {
			resource = _pool.remove(descriptor);
		}
		if (resource != null) {
			resource.close();
		}
	}

	@Override
	public synchronized void close() {
		for (R resource : _pool.values()) {
			try {
				resource.close();
			} catch (Exception e) {
				// ignore
			}
		}
		_pool.clear();
	}
	
}
