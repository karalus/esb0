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
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

public final class DataStructures {

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
				return Collections.emptyMap();
			}
		}
		return result;
	}

	public static <K, V, C extends Collection<V>> boolean putInCollection(Map<K, C> map, K key, V value, Supplier<C> collCon, Predicate<V> equals) {
		C coll = map.get(key);
		if (coll == null) {
			coll = collCon.get();
			map.put(key, coll);
		} else {
			for (V v : coll) {
				if (equals.test(v)) {
					return false;
				}
			}
		}
		return coll.add(value);
	}

	public static <T> List<T> moveToNewList(Collection<T> coll, boolean noop) {
		if (noop) {
			return Collections.emptyList();
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

	public static String asXMLString(Throwable e) {
		Set<Throwable> dejaVu = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
		return "<exception>" + asXMLString(e, dejaVu) + "</exception>";
	}

	private static String asXMLString(Throwable e, Set<Throwable> dejaVu) {
		if (dejaVu.add(e)) {
			String xml = "<message><![CDATA[" + e + "]]></message>";
			for (Throwable t : e.getSuppressed()) {
				xml += "<suppressed>" + asXMLString(t, dejaVu) + "</suppressed>";
			}
			if (e.getCause() != null) {
				xml += "<cause>" + asXMLString(e.getCause(), dejaVu) + "</cause>";
			}
			return xml;
		} else {
			return "<circular/>";
		}
	}

	public static String asString(Throwable e) {
		Set<Throwable> dejaVu = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
		return asString(e, dejaVu);
	}

	private static String asString(Throwable e, Set<Throwable> dejaVu) {
		if (dejaVu.add(e)) {
			String str = e.toString();
			for (Throwable t : e.getSuppressed()) {
				str += "\nSuppressed: " + asString(t, dejaVu);
			}
			if (e.getCause() != null) {
				str += "\nCause: " + asString(e.getCause(), dejaVu);
			}
			return str;
		} else {
			return "\nCircular";
		}
	}

}
