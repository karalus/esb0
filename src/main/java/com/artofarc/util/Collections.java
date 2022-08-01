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
package com.artofarc.util;

import java.lang.reflect.Array;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class Collections {

	public static <K, V> Map.Entry<K, V> createEntry(final K key, final V value) {
		return new AbstractMap.SimpleImmutableEntry<>(key, value);
	}

	@SuppressWarnings("unchecked")
	public static <K, V> Map<V, K> createMap(Object... keyValues) {
		Map<Object, Object> result = new HashMap<>();
		for (int i = 0; i < keyValues.length; i += 2) {
			result.put(keyValues[i], keyValues[i + 1]);
		}
		return (Map<V, K>) result;
	}

	/**
	 * In case of a collision return empty map.
	 */
	public static <K, V> Map<V, K> inverseMap(Map<K, V> map) {
		Map<V, K> result = new HashMap<>();
		for (Map.Entry<K, V> entry : map.entrySet()) {
			if (result.put(entry.getValue(), entry.getKey()) != null) {
				return java.util.Collections.emptyMap();
			}
		}
		return result;
	}

	public static <T> List<T> moveToNewList(Collection<T> coll, boolean noop) {
		if (noop) {
			return java.util.Collections.emptyList();
		}
		ArrayList<T> list = new ArrayList<>(coll);
		coll.clear();
		return list;
	}

	public static <T> T[] toArray(Collection<T> list) {
		Iterator<T> iter = list.iterator();
		if (!iter.hasNext()) {
			throw new IllegalArgumentException("Collection must not be empty");
		}
		@SuppressWarnings("unchecked")
		T[] array = (T[]) Array.newInstance(iter.next().getClass(), list.size());
		return list.toArray(array);
	}

	public static <T> T[] toSingletonArray(T value) {
		@SuppressWarnings("unchecked")
		T[] array = (T[]) Array.newInstance(value.getClass(), 1);
		array[0] = value;
		return array;
	}

	@SuppressWarnings("unchecked")
	public static <T> Stream<T> typeSelect(Collection<? super T> coll, Class<T> cls) {
		return (Stream<T>) coll.stream().filter(cls::isInstance);
	}

}
