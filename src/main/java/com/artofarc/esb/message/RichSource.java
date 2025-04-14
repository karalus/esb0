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
package com.artofarc.esb.message;

import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMSource;
import javax.xml.xquery.XQException;
import javax.xml.xquery.XQItem;
import javax.xml.xquery.XQItemType;

import org.w3c.dom.Node;
import org.xml.sax.SAXException;

public final class RichSource {

	public interface XQItemSupplier {
		XQItem getXQItem() throws SAXException;
	}

	private final Source _source;
	private final boolean _xqItemKindElement;
	private final XQItemSupplier _itemSupplier;

	public RichSource(Source source, XQItem xqItem, XQItemSupplier itemSupplier) throws XQException {
		_source = source;
		_xqItemKindElement = xqItem.getItemType().getItemKind() == XQItemType.XQITEMKIND_ELEMENT;
		_itemSupplier = itemSupplier;
	}

	public RichSource(Source source) {
		_source = source;
		_xqItemKindElement = false;
		_itemSupplier = null;
	}

	public RichSource(Node node) {
		_source = new DOMSource(node);
		_xqItemKindElement = node.getNodeType() == Node.ELEMENT_NODE;
		_itemSupplier = null;
	}

	public Source getSource() {
		return _source;
	}

	public boolean isXQItemKindElement() {
		return _xqItemKindElement;
	}

	public XQItemSupplier getXQItemSupplier() {
		return _itemSupplier;
	}

}
