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

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.artofarc.esb.context.GlobalContext;

public class LRUCacheWithExpirationFactory<K, V> extends ResourceFactory<LRUCacheWithExpirationFactory<K, V>.Cache, String, Integer, RuntimeException> implements Runnable {

	static final class Expiration<K> implements Delayed {

		private final long _expiration;
		private final K _key;
		private final String _cacheName;

		Expiration(long expiration, K key, String cacheName) {
			_expiration = expiration;
			_key = key;
			_cacheName = cacheName;
		}

		@Override
		public long getDelay(TimeUnit unit) {
			return unit.convert(_expiration - System.nanoTime(), TimeUnit.NANOSECONDS);
		}

		@Override
		public int compareTo(Delayed other) {
			return Long.compare(_expiration, ((Expiration<?>) other)._expiration);
		}

		@Override
		public String toString() {
			return "{key=" + _key + ", delay=" + getDelay(TimeUnit.SECONDS) + "}";
		}
	}

	public final class Cache implements AutoCloseable {

		private final String _name;
		private final int _maxSize;
		private final Map<K, Expiration<K>> _expirationKeys = new ConcurrentHashMap<>();
		private final Map<K, V> _cache = Collections.synchronizedMap(new LinkedHashMap<K, V>() {
			private static final long serialVersionUID = 1L;

			@Override
			protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
				boolean remove = size() > _maxSize;
				if (remove) {
					Expiration<K> expiration = _expirationKeys.remove(eldest.getKey());
					expiries.remove(expiration);
				}
				return remove;
			}
		});

		Cache(String name, int maxSize) {
			_name = name;
			_maxSize = maxSize;
		}

		public Object put(K key, V value, long ttl) {
			long ttlNanos = TimeUnit.NANOSECONDS.convert(ttl, TimeUnit.SECONDS);
			Expiration<K> expiration = new Expiration<K>(System.nanoTime() + ttlNanos, key, _name);
			Expiration<K> expirationOld = _expirationKeys.put(key, expiration);
			synchronized (LRUCacheWithExpirationFactory.this) {
				if (expirationOld != null) {
					expiries.remove(expirationOld);
				}
				expiries.add(expiration);
				return _cache.put(key, value);
			}
		}

		public boolean containsKey(K key) {
			return _cache.containsKey(key);
		}

		public V get(K key) {
			return _cache.get(key);
		}

		public V remove(K key) {
			Expiration<K> expiration = _expirationKeys.remove(key);
			if (expiration != null) {
				synchronized (LRUCacheWithExpirationFactory.this) {
					expiries.remove(expiration);
				}
			}
			return _cache.remove(key);
		}

		public Collection<Expiration<K>> getExpirations() {
			return _expirationKeys.values();
		}

		@Override
		public void close() {
		}
	}

	private final DelayQueue<Expiration<K>> expiries = new DelayQueue<>();
	private final Future<?> cleaner;

	public LRUCacheWithExpirationFactory(GlobalContext globalContext) {
		cleaner = globalContext.getDefaultWorkerPool().getExecutorService().submit(this);
	}

	@Override
	public void close() {
		cleaner.cancel(true);
	}

	@Override
	public void run() {
		try {
			for (;;) {
				Expiration<K> expiration = expiries.take();
				synchronized (this) {
					Cache lruCache = getResource(expiration._cacheName);
					lruCache._cache.remove(expiration._key);
					Expiration<K> expirationOld = lruCache._expirationKeys.remove(expiration._key);
					if (expirationOld != null && expirationOld != expiration) {
						expiries.remove(expirationOld);
					}
				}
			}
		} catch (InterruptedException e) {
			// cancelled
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	protected Cache createResource(String cacheName, Integer maxSize) {
		if (maxSize == null) {
			throw new IllegalArgumentException("Cache cannot be created without parameter maxSize: " + cacheName);
		}
		return new Cache(cacheName, maxSize);
	}

}
