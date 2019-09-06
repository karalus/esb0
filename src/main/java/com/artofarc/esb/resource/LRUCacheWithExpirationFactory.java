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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.artofarc.esb.context.GlobalContext;

public final class LRUCacheWithExpirationFactory extends ResourceFactory<LRUCacheWithExpirationFactory.Cache, String, Integer> implements Runnable {

	static final class Expiration implements Delayed {

		private final long _expiration;
		private final Object _key;
		private final String _cacheName;

		Expiration(long expiration, Object key, String cacheName) {
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
			return Long.compare(_expiration, ((Expiration) other)._expiration);
		}
	}

	public final class Cache implements AutoCloseable {

		private final String _name;
		private final int _maxSize;
		private final Map<Object, Expiration> _expirationKeys = new ConcurrentHashMap<>();
		private final Map<Object, Object> _cache = Collections.synchronizedMap(new LinkedHashMap<Object, Object>() {
			private static final long serialVersionUID = 1L;

			@Override
			protected boolean removeEldestEntry(Map.Entry<Object, Object> eldest) {
				boolean remove = size() > _maxSize;
				if (remove) {
					Expiration expiration = _expirationKeys.remove(eldest.getKey());
					expiries.remove(expiration);
				}
				return remove;
			}
		});

		Cache(String name, int maxSize) {
			_name = name;
			_maxSize = maxSize;
		}

		public Object put(Object key, Object value, long ttl) {
			long ttlNanos = TimeUnit.NANOSECONDS.convert(ttl, TimeUnit.MILLISECONDS);
			Expiration expiration = new Expiration(System.nanoTime() + ttlNanos, key, _name);
			Expiration expirationOld = _expirationKeys.put(key, expiration);
			synchronized (LRUCacheWithExpirationFactory.this) {
				if (expirationOld != null) {
					expiries.remove(expirationOld);
				}
				expiries.add(expiration);
				return _cache.put(key, value);
			}
		}

		public boolean containsKey(Object key) {
			return _cache.containsKey(key);
		}

		public Object get(Object key) {
			return _cache.get(key);
		}

		public Object remove(Object key) {
			Expiration expiration = _expirationKeys.remove(key);
			if (expiration != null) {
				synchronized (LRUCacheWithExpirationFactory.this) {
					expiries.remove(expiration);
				}
			}
			return _cache.remove(key);
		}

		@Override
		public void close() {
		}
	}

	private final DelayQueue<Expiration> expiries = new DelayQueue<Expiration>();
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
				Expiration expiration = expiries.take();
				synchronized (this) {
					Cache lruCache = getResource(expiration._cacheName);
					lruCache._cache.remove(expiration._key);
					Expiration expirationOld = lruCache._expirationKeys.remove(expiration._key);
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
