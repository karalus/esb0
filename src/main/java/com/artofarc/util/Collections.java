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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class Collections {

	public static <K, V> Map.Entry<K, V> createEntry(final K key, final V value) {
		return new AbstractMap.SimpleImmutableEntry<>(key, value);
	}

	public static <K, V> Map<V, K> inverseMap(Collection<Map.Entry<K, V>> entrySet, boolean unique) {
		Map<V, K> result = new HashMap<>();
		for (Map.Entry<K, V> entry : entrySet) {
			if (result.put(entry.getValue(), entry.getKey()) != null) {
				if (unique) {
					throw new IllegalArgumentException("Value is not unique: " + entry.getValue());
				} 
				return java.util.Collections.emptyMap();
			}
		}
		return result;
	}

	public static <T> List<T> newList(Collection<T> coll, boolean move) {
		if (move) {
			ArrayList<T> list = new ArrayList<>(coll);
			coll.clear();
			return list;
		}
		return java.util.Collections.emptyList();
	}

	public static <T> T[] toArray(Collection<T> list) {
		Iterator<T> iter = list.iterator();
		if (!iter.hasNext()) {
			throw new IllegalArgumentException("Collection must not be empty");
		}
		@SuppressWarnings("unchecked")
		T[] array = (T[]) java.lang.reflect.Array.newInstance(iter.next().getClass(), list.size());
		return list.toArray(array);
	}

}
