/*
 * Copyright 2025 Andre Karalus
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

import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;

import com.sun.xml.fastinfoset.sax.SAXDocumentParser;

public class FastInfosetDeserializer extends SAXDocumentParser {

	@Override
	public void setFeature(String name, boolean value) throws SAXNotRecognizedException, SAXNotSupportedException {
		switch (name) {
		case "http://xml.org/sax/features/validation":
		case "http://apache.org/xml/features/disallow-doctype-decl":
			// Don't throw SAXNotRecognizedException, this takes too much performance
			break;
		default:
			super.setFeature(name, value);
			break;
		}
	}

}
