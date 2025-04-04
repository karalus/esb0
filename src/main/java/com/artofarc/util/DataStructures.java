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
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

public final class DataStructures {

	public static <T> List<T> createList(int size) {
		return size == 0 ? Collections.emptyList() : new ArrayList<>(size);
	}

	public static <K, V> Map.Entry<K, V> createEntry(final K key, final V value) {
		return new AbstractMap.SimpleImmutableEntry<>(key, value);
	}

	public static <K, V> Map<K, V> createHashMap(int size) {
		return size == 0 ? Collections.emptyMap() : size > 12 ? new HashMap<>(size * 4 / 3) : new HashMap<>();
	}

	/**
	 * In case of a collision return empty map.
	 */
	public static <K, V> Map<V, K> inverseMap(Map<K, V> map) {
		Map<V, K> result = createHashMap(map.size());
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

	public static <T extends Comparable<? super T>> List<T> asSortedList(Collection<T> coll) {
		List<T> list = new ArrayList<>(coll);
		Collections.sort(list);
		return list;
	}

	public static <T> List<T> asSortedList(Collection<T> coll, Comparator<T> comp) {
		List<T> list = new ArrayList<>(coll);
		Collections.sort(list, comp);
		return list;
	}

	public static <T> List<T> moveToNewList(Collection<T> coll) {
		List<T> list = new ArrayList<>(coll);
		coll.clear();
		return list;
	}

	public static <T> T[] toArray(List<T> list) {
		if (list.isEmpty()) {
			throw new IllegalArgumentException("List must not be empty");
		}
		@SuppressWarnings("unchecked")
		T[] array = (T[]) Array.newInstance(list.get(0).getClass(), list.size());
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
