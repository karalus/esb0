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
package com.artofarc.util;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

public final class Collections {

	public static final <K, V> Map.Entry<K, V> createEntry(final K key, final V value) {
		return new AbstractMap.SimpleImmutableEntry<K, V>(key, value);
	}

	public static final <K, V> Map<V, K> inverseMap(Map<K, V> map) {
		final Map<V, K> result = new HashMap<>();
		for (final Entry<K, V> entry : map.entrySet()) {
			if (result.put(entry.getValue(), entry.getKey()) != null) {
				// throw new IllegalArgumentException("Values are not unique");
			}
		}
		return result;
	}

}
