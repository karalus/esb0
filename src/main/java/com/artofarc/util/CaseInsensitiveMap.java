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

import java.util.HashMap;

@SuppressWarnings("serial")
public class CaseInsensitiveMap<V> extends HashMap<String, V> {

	@Override
	public boolean containsKey(Object key) {
		if (!(key instanceof String))
			return false;
		String skey = (String) key;
		return super.containsKey(skey.toLowerCase());
	}

	@Override
	public V get(Object key) {
		if (!(key instanceof String))
			return null;
		String skey = (String) key;
		return super.get(skey.toLowerCase());
	}

	@Override
	public V put(String key, V value) {
		String skey = key != null ? key.toLowerCase() : null;
		return super.put(skey, value);
	}

	@Override
	public V remove(Object key) {
		if (!(key instanceof String))
			return null;
		String skey = (String) key;
		return super.remove(skey.toLowerCase());
	}

}
