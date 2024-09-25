/*
 * Copyright 2024 Andre Karalus
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

import javax.xml.XMLConstants;

import org.xml.sax.Attributes;

/**
 * Helps to treat xsi attributes and regular attributes differently.
 */
public class AttributesHelper {

	private final Attributes attributes;
	private final boolean[] use;
	private int len;
	private String type, nil;

	public AttributesHelper(Attributes atts) {
		attributes = atts;
		use = atts.getLength() > 0 ? new boolean[atts.getLength()] : null;
		for (int i = 0; i < atts.getLength(); ++i) {
			if (XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI.equals(atts.getURI(i))) {
				// https://www.w3.org/TR/xmlschema-1/#Instance_Document_Constructions
				if ("type".equals(atts.getLocalName(i))) {
					type = atts.getValue(i);
				} else if ("nil".equals(atts.getLocalName(i))) {
					nil = atts.getValue(i);
				}
			} else if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(atts.getURI(i))) {
				use[i] = true;
				++len;
			}
		}
	}

	public int getLength() {
		return len;
	}

	public String getType() {
		return type;
	}

	public String getNil() {
		return nil;
	}

	public void forEach(TriConsumer<String, String, String> consumer) {
		for (int i = 0; i < attributes.getLength(); ++i) {
			if (use[i]) {
				consumer.accept(attributes.getURI(i), attributes.getLocalName(i), attributes.getValue(i));
			}
		}
	}

	@FunctionalInterface
	public interface TriConsumer<U, L, V> {
		void accept(U u, L l, V v);
	}

}
