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
import java.lang.reflect.Type;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.artofarc.esb.resource.ResourceFactory;

public abstract class AbstractContext extends ResourceFactory<ResourceFactory<?, ?, ?, ?>, Class<?>, AbstractContext, RuntimeException> {

	protected final static Logger logger = LoggerFactory.getLogger(Context.class);

	@SuppressWarnings("unchecked")
	public final <RF extends ResourceFactory<?, ?, ?, ?>> RF getResourceFactory(Class<RF> rfc) {
		return (RF) getResource(rfc, this);
	}

	@Override
	protected ResourceFactory<?, ?, ?, ?> createResource(Class<?> rfc, AbstractContext context) {
		try {
			for (Constructor<?> constructor : rfc.getConstructors()) {
				Type[] paramTypes = constructor.getGenericParameterTypes();
				if (paramTypes.length == 1) {
					if (((Class<?>) paramTypes[0]).isAssignableFrom(context.getClass())) {
						return (ResourceFactory<?, ?, ?, ?>) constructor.newInstance(context);
					}
				} else if (paramTypes.length == 0) {
					return (ResourceFactory<?, ?, ?, ?>) constructor.newInstance();
				}
			}
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
		throw new RuntimeException(getClass().getSimpleName() + ": No appropriate ctor found in " + rfc);
	}

}
