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
package com.artofarc.esb.context;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.util.HashMap;

import com.artofarc.esb.resource.ResourceFactory;

public class AbstractContext implements AutoCloseable {

	private final HashMap<Class<?>, ResourceFactory<?, ?, ?>> _pool = new HashMap<>();

	@SuppressWarnings("unchecked")
	public synchronized <RF extends ResourceFactory<?, ?, ?>> RF getResourceFactory(Class<RF> rfc) {
		ResourceFactory<?, ?, ?> resourceFactory = _pool.get(rfc);
		if (resourceFactory == null) {
			try {
				for (Constructor<?> constructor : rfc.getConstructors()) {
					Type[] paramTypes = constructor.getGenericParameterTypes();
					if (paramTypes.length == 1) {
						if (AbstractContext.class.isAssignableFrom((Class<?>) paramTypes[0])) {
							resourceFactory = (ResourceFactory<?, ?, ?>) constructor.newInstance(this);
							break;
						}
					} else if (paramTypes.length == 0) {
						resourceFactory = (ResourceFactory<?, ?, ?>) constructor.newInstance();
						break;
					}
				}
			} catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
				throw new RuntimeException(e);
			}
			if (resourceFactory == null) {
				throw new RuntimeException("No appropriate ctor found for " + rfc);
			}
			_pool.put(rfc, resourceFactory);
		}
		return (RF) resourceFactory;
	}

	@Override
	public synchronized void close() {
		for (ResourceFactory<?, ?, ?> resourceFactory : _pool.values()) {
			try {
				resourceFactory.close();
			} catch (Exception e) {
				// ignore
				e.printStackTrace();
			}
		}
	}

}
