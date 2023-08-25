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
package com.artofarc.esb.action;

import javax.xml.transform.sax.SAXSource;
import javax.xml.xquery.XQItem;

import org.xml.sax.XMLReader;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.XOPDeserializer;
import com.artofarc.util.XMLFilterBase;

/**
 * Deserialize XOP package.
 *
 * @see <a href="https://www.w3.org/TR/xop10/">XOP</a>
 */
public class XOPDeserializeAction extends SAXAction {

	@Override
	protected SAXSource createSAXSource(Context context, ESBMessage message, XQItem item) throws Exception {
		XOPDeserializer xopDeserializer = new XOPDeserializer(message);
		xopDeserializer.setParent(new XQJFilter(item));
		return new SAXSource(xopDeserializer, null);
	}

	@Override
	protected XMLFilterBase createXMLFilter(Context context, ESBMessage message, XMLReader parent) throws Exception {
		XOPDeserializer xopDeserializer = new XOPDeserializer(message);
		if (parent != null) {
			xopDeserializer.setParent(parent);
		} else {
			xopDeserializer.setParent(context.getSAXParser());
		}
		return xopDeserializer;
	}

}
