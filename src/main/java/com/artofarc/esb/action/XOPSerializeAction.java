/*
 * Copyright 2023 Andre Karalus
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
package com.artofarc.esb.action;

import javax.xml.transform.sax.SAXSource;
import javax.xml.xquery.XQItem;

import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.RichSource;
import com.artofarc.esb.message.XOPSerializer;
import com.artofarc.esb.service.MultipartSubtype;
import com.artofarc.util.XMLFilterBase;

public class XOPSerializeAction extends SAXAction {

	private final int _threshold;
	private final String _contentType, _ifAccepts;

	public XOPSerializeAction(int threshold, String contentType, MultipartSubtype ifAccepts) {
		_threshold = threshold;
		_contentType = contentType;
		_ifAccepts = ifAccepts == null ? null : (HttpConstants.MEDIATYPE_MULTIPART + ifAccepts.value()).intern();
	}

	@Override
	protected RichSource createSource(Context context, ESBMessage message, XQItem item) throws Exception {
		if (message.getSchema() == null) {
			throw new ExecutionException(this, "No schema specified");
		}
		final String accept = message.getVariable(HttpConstants.HTTP_HEADER_ACCEPT);
		if (accept == null || _ifAccepts == null || HttpConstants.isAcceptable(accept, _ifAccepts)) {
			XOPSerializer xopSerializer = new XOPSerializer(message, _threshold, (String) eval(_contentType, context, message));
			xopSerializer.setParent(new XQJFilter(item));
			return new RichSource(new SAXSource(xopSerializer, null), item, null, xopSerializer.getTypeInfoProvider());
		} else {
			return new RichSource(new SAXSource(new XQJFilter(item), null), item, null);
		}
	}

	@Override
	protected RichSource createSource(Context context, ESBMessage message, XMLReader parent, InputSource inputSource, boolean xqItemKindElement) throws Exception {
		if (message.getSchema() == null) {
			throw new ExecutionException(this, "No schema specified");
		}
		final String accept = message.getVariable(HttpConstants.HTTP_HEADER_ACCEPT);
		if (accept == null || _ifAccepts == null || HttpConstants.isAcceptable(accept, _ifAccepts)) {
			XOPSerializer xopSerializer = new XOPSerializer(message, _threshold, (String) eval(_contentType, context, message));
			if (parent != null) {
				xopSerializer.setParent(parent);
			} else {
				xopSerializer.setParent(context.getSAXParser());
			}
			return new RichSource(new SAXSource(xopSerializer, inputSource), xqItemKindElement, xopSerializer.getTypeInfoProvider());
		} else {
			return new RichSource(new SAXSource(parent != null ? new XMLFilterBase(parent) : new ReuseParserXMLFilter(context.getSAXParser()), inputSource), xqItemKindElement);
		}
	}

}
