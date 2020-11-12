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
		if (ref == null) {
			ref = new WeakReference<V>(create(key), queue);
			cache.put(key, ref);
		}
		return ref.get();
	}

	abstract public V create(K key);

}
