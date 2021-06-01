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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public final class NamespaceMap implements javax.xml.namespace.NamespaceContext {

	private final Map<String, String> _prefixMap;
	private final Map<String, ArrayList<String>> _nsMap = new HashMap<>();

	public NamespaceMap(Map<String, String> prefixMap) {
		_prefixMap = prefixMap;
		for (Map.Entry<String, String> entry : prefixMap.entrySet()) {
			ArrayList<String> list = _nsMap.get(entry.getValue());
			if (list == null) {
				list = new ArrayList<>();
				_nsMap.put(entry.getValue(), list);
			}
			int i = 0;
			// shortest prefix first
			while (i < list.size() && list.get(i).length() < entry.getKey().length()) ++i;
			list.add(i, entry.getKey());
		}
	}

	@Override
	public String getNamespaceURI(String prefix) {
		return _prefixMap.get(prefix);
	}

	@Override
	public String getPrefix(String namespaceURI) {
		final ArrayList<String> list = _nsMap.get(namespaceURI);
		return list != null ? list.get(0) : null;
	}

	@Override
	public Iterator<String> getPrefixes(String namespaceURI) {
		final ArrayList<String> list = _nsMap.get(namespaceURI);
		return list != null ? list.iterator() : Collections.emptyIterator();
	}

	public Set<Map.Entry<String, String>> getPrefixes() {
		return _prefixMap.entrySet();
	}

}
