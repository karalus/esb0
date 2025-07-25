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

import org.xml.sax.SAXException;

public final class XopAwareValidatorHandler extends TypeAwareXMLFilter {

	private final Set<String> _cids;
	private final ThrowingFunction<String, String, Exception> _cid2contentType;

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
	public void startXOPInclude(String href) throws SAXException {
		if (!isBase64Binary()) {
			// disputable, I've seen xs:string with XOP
			reportError("Enclosing element has not base type xs:base64Binary");
		}
		if (href == null) {
			reportError("Missing required attribute href");
		} else if (!href.startsWith("cid:")) {
			reportError("href must have schema cid:, but is " + href);
		} else if (!_cids.contains(href = href.substring(4))) {
			reportError("Not found in attachments " + href);
		} else if (_contentType != null && _cid2contentType != null) {
			String contentType;
			try {
				contentType = _cid2contentType.apply(href);
			} catch (Exception e) {
				throw new SAXException("Could not resolve content type for cid " + href, e);
			}
			if (!contentType.startsWith(_contentType)) {
				reportError("Attachment has deviant content type " + contentType);
			}
		}
	}

}
