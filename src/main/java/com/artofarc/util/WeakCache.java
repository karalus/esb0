/*
 * Copyright 2023 Andre Karalus
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

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;

public abstract class WeakCache<K, V> {

	private final HashMap<K, Reference<? extends V>> cache = new HashMap<>();
	private final ReferenceQueue<V> queue = new ReferenceQueue<>();

	public final synchronized V get(K key) {
		Reference<? extends V> ref;
		while ((ref = queue.poll()) != null) {
			for (Iterator<Reference<? extends V>> iter = cache.values().iterator(); iter.hasNext();) {
				if (iter.next() == ref) {
					iter.remove();
					break;
				}
			}
		}
		ref = cache.get(key);
		V value;
		if (ref == null) {
			value = create(key);
			cache.put(key, new WeakReference<>(value, queue));
		} else {
			value = ref.get();
			if (value == null) {
				value = create(key);
				cache.replace(key, new WeakReference<>(value, queue));
			}
		}
		return value;
	}

	abstract public V create(K key);

}
