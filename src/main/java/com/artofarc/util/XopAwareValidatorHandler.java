/*
 * Copyright 2022 Andre Karalus
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

import java.util.Set;

import javax.xml.validation.Schema;
import javax.xml.validation.TypeInfoProvider;

import org.w3c.dom.TypeInfo;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

public final class XopAwareValidatorHandler extends TypeAwareXMLFilter {

	private final Set<String> _cids;
	private final ThrowingFunction<String, String, Exception> _cid2contentType;
	private String _contentType;

	@FunctionalInterface
	public interface ThrowingFunction<T, R, E extends Exception> {
		R apply(T t) throws E;
	}

	public XopAwareValidatorHandler(Schema schema, Set<String> cids, ThrowingFunction<String, String, Exception> cid2contentType) {
		super(schema);
		_cids = cids;
		_cid2contentType = cid2contentType;
	}

	@Override
	public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
		if (W3CConstants.isXOPInclude(uri, localName)) {
			if (typeInfo == null || !isXSType("base64Binary")) {
				reportError("Enclosing element not of type xs:base64Binary");
			}
			String cid = atts.getValue(W3CConstants.NAME_HREF);
			if (cid == null) {
				reportError("Missing required attribute href");
			} else if (!cid.startsWith("cid:")) {
				reportError("href must have schema cid, but is " + cid);
			} else if (!_cids.contains(cid = cid.substring(4))) {
				reportError("Not found in attachments " + cid);
			} else if (_contentType != null && _cid2contentType != null) {
				String contentType;
				try {
					contentType = _cid2contentType.apply(cid);
				} catch (Exception e) {
					throw new SAXException("Could not resolve content type for cid " + cid, e);
				}
				if (!contentType.startsWith(_contentType)) {
					reportError("Attachment has deviant content type " + contentType);
				}
			}
			if (getReceiverContentHandler() != null) {
				_skipValidationHandler = true;
				getReceiverContentHandler().startElement(uri, localName, qName, atts);
			}
		} else {
			_contentType = atts.getValue(W3CConstants.URI_NS_XMLMIME, W3CConstants.NAME_CONTENT_TYPE);
			super.startElement(uri, localName, qName, atts);
		}
	}

	@Override
	public void endElement(String uri, String localName, String qName) throws SAXException {
		typeInfo = null;
		_contentType = null;
		if (!W3CConstants.isXOPInclude(uri, localName)) {
			super.endElement(uri, localName, qName);
		} else if (getReceiverContentHandler() != null) {
			getReceiverContentHandler().endElement(uri, localName, qName);
			_skipValidationHandler = false;
		}
	}

	private boolean _skipValidationHandler;

	private final TypeInfoProvider _typeInfoProvider = new TypeInfoProvider() {

		@Override
		public boolean isSpecified(int index) {
			return _skipValidationHandler ? true : _validatorHandler.getTypeInfoProvider().isSpecified(index);
		}

		@Override
		public boolean isIdAttribute(int index) {
			return _skipValidationHandler ? false : _validatorHandler.getTypeInfoProvider().isIdAttribute(index);
		}

		@Override
		public TypeInfo getElementTypeInfo() {
			return _skipValidationHandler ? null : _validatorHandler.getTypeInfoProvider().getElementTypeInfo();
		}

		@Override
		public TypeInfo getAttributeTypeInfo(int index) {
			return _skipValidationHandler ? null : _validatorHandler.getTypeInfoProvider().getAttributeTypeInfo(index);
		}
	};

	public TypeInfoProvider getTypeInfoProvider() {
		return _typeInfoProvider;
	}

}
