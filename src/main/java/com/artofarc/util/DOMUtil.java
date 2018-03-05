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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class DOMUtil {

	public final static Element getLastChildElement(Node node) {
		final NodeList childNodes = node.getChildNodes();
		for (int i = childNodes.getLength(); i > 0;) {
			final Node childNode = childNodes.item(--i);
			if (childNode.getNodeType() == Node.ELEMENT_NODE) {
				return (Element) childNode;
			}
		}
		throw new NoSuchElementException("No child element found");
	}

	public final static List<Element> getChildElements(Node node) {
		final NodeList childNodes = node.getChildNodes();
		final List<Element> result = new ArrayList<>();
		for (int i = 0; i < childNodes.getLength(); ++i) {
			final Node childNode = childNodes.item(i);
			if (childNode.getNodeType() == Node.ELEMENT_NODE) {
				result.add((Element) childNode);
			}
		}
		return result;
	}

	public final static Map<String, Element> getChildElementsByLocalName(Node node) {
		final NodeList childNodes = node.getChildNodes();
		final Map<String, Element> result = new LinkedHashMap<>();
		for (int i = 0; i < childNodes.getLength(); ++i) {
			final Node childNode = childNodes.item(i);
			if (childNode.getNodeType() == Node.ELEMENT_NODE) {
				Element childElement = (Element) childNode;
				result.put(childElement.getLocalName(), childElement);
			}
		}
		return result;
	}

}
