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
import javax.xml.validation.TypeInfoProvider;
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
	private final TypeInfoProvider _typeInfoProvider;

	public RichSource(Source source, XQItem xqItem, XQItemSupplier itemSupplier, TypeInfoProvider typeInfoProvider) throws XQException {
		_source = source;
		_xqItemKindElement = xqItem.getItemType().getItemKind() == XQItemType.XQITEMKIND_ELEMENT;
		_itemSupplier = itemSupplier;
		_typeInfoProvider = typeInfoProvider;
	}

	public RichSource(Source source, XQItem xqItem, XQItemSupplier itemSupplier) throws XQException {
		this(source, xqItem, itemSupplier, null);
	}

	public RichSource(Source source, boolean xqItemKindElement, TypeInfoProvider typeInfoProvider) {
		_source = source;
		_xqItemKindElement = xqItemKindElement;
		_itemSupplier = null;
		_typeInfoProvider = typeInfoProvider;
	}

	public RichSource(Source source, boolean xqItemKindElement) {
		this(source, xqItemKindElement, null);
	}

	public RichSource(Source source) {
		this(source, false);
	}

	public RichSource(Node node) {
		_source = new DOMSource(node);
		_xqItemKindElement = node.getNodeType() == Node.ELEMENT_NODE;
		_itemSupplier = null;
		_typeInfoProvider = null;
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

	public TypeInfoProvider getTypeInfoProvider() {
		return _typeInfoProvider;
	}

}
