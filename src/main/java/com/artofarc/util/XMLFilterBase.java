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

import java.lang.reflect.Field;

import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLFilterImpl;

public class XMLFilterBase extends XMLFilterImpl {

	private final static Field VALUE;

	static {
		try {
			Field value = String.class.getDeclaredField("value");
			if (value.getType().getComponentType() == Character.TYPE) {
				VALUE = value;
				VALUE.setAccessible(true);
			} else {
				// JDK11
				VALUE = null;
			}
		} catch (NoSuchFieldException e) {
			throw new RuntimeException(e);
		}
	}

	public XMLFilterBase() {
	}

	public XMLFilterBase(XMLReader parent) {
		super(parent);
	}

	@Override
	public void setFeature(String name, boolean value) {
		// Don't throw SAXNotRecognizedException, we assume that the feature "namespace" is standard anyways
	}

	@Override
	public void setProperty(String name, Object value) throws SAXNotRecognizedException, SAXNotSupportedException {
		// Don't throw SAXNotRecognizedException when parent is null, this takes to much performance
		if (getParent() != null) {
			getParent().setProperty(name, value);
		}
	}

	public final void characters(String str) throws SAXException {
		if (str.length() > 0) {
			if (VALUE != null) {
				// Avoid copying to reduce GC overhead
				characters(ReflectionUtils.get(VALUE, str), 0, str.length());
			} else {
				characters(str.toCharArray(), 0, str.length());
			}
		}
	}

}
