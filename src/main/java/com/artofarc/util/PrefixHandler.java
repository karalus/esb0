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
import java.util.Map;

import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

public class PrefixHandler extends DefaultHandler {

	private final ArrayList<Map.Entry<String, String>> prefixes = new ArrayList<>();

	@Override
	public void startPrefixMapping(String prefix, String uri) throws SAXException {
		prefixes.add(Collections.createEntry(prefix, uri));
	}

	@Override
	public void endPrefixMapping(String prefix) throws SAXException {
		for (int i = prefixes.size(); i > 0;) {
			if (prefixes.get(--i).getKey().equals(prefix)) {
				prefixes.remove(i);
				return;
			}
		}
		throw new SAXException("prefix not found: " + prefix);
	}

	public final String getNamespace(String prefix) {
		for (int i = prefixes.size(); i > 0;) {
			final Map.Entry<String, String> entry = prefixes.get(--i);
			if (entry.getKey().equals(prefix)) {
				return entry.getValue();
			}
		}
		return null;
	}

}
