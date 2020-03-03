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

import com.artofarc.util.ConcurrentResourcePool;

public abstract class ResourceFactory<R extends AutoCloseable, D, P, E extends Exception> extends ConcurrentResourcePool<R, D, P, E> implements AutoCloseable {

	public final void close(D descriptor) throws Exception {
		R resource = removeResource(descriptor);
		if (resource != null) {
			resource.close();
		}
	}

	@Override
	public void close() {
		for (R resource : getResources()) {
			try {
				resource.close();
			} catch (Exception e) {
				// ignore
			}
		}
	}

}
