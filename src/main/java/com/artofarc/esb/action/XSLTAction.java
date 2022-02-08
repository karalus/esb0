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

import java.io.IOException;
import java.util.List;

import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.xquery.XQItem;

import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.JAXPFactoryHelper;
import com.artofarc.util.XMLFilterBase;

public class XSLTAction extends SAXAction {

	private final Templates _templates;
	private final List<String> _params;

	public XSLTAction(Templates templates, List<String> params) {
		_templates = templates;
		_params = params;
	}

	class TransformerFilter extends XMLFilterBase {
		final TransformerHandler transformerHandler;
		final Transformer transformer;

		TransformerFilter(Context context, ESBMessage message, XMLReader parent) throws TransformerException {
			if (parent != null) {
				super.setParent(parent);
				transformerHandler = JAXPFactoryHelper.newTransformerHandler(_templates);
				transformer = transformerHandler.getTransformer();
			} else {
				transformerHandler = null;
				transformer = _templates.newTransformer();
			}
			transformer.setURIResolver(context.getGlobalContext().getURIResolver());
			for (String param : _params) {
				Object value = message.getVariable(param);
				if (value instanceof Node) {
					// We need to make a copy otherwise Saxon complains with:
					// "A node supplied in a global parameter must be built using the same Configuration that was used to compile the stylesheet or query"
					DOMResult domResult = new DOMResult();
					context.transformRaw(new DOMSource((Node) value), domResult);
					value = domResult.getNode();
				} else if (value == null) {
					value = "";
				}
				transformer.setParameter(param, value);
			}
		}

		@Override
		public void setParent(XMLReader parent) {
			if (parent == null ^ getParent() == null) {
				throw new IllegalArgumentException("having a parent cannot be changed after construction");
			}
			super.setParent(parent);
		}

		@Override
		public void parse(InputSource source) throws SAXException, IOException {
			if (transformerHandler != null) {
				transformerHandler.setResult(new SAXResult(getContentHandler()));
				getParent().setContentHandler(transformerHandler);
				getParent().parse(source);
			} else {
				try {
					transformer.transform(inputSourceToSource(source), new SAXResult(getContentHandler()));
				} catch (TransformerException e) {
					throw new SAXException(e);
				}
			}
		}
	}

	@Override
	protected SAXSource createSAXSource(Context context, ESBMessage message, XQItem item) throws TransformerException {
		TransformerFilter transformerFilter = new TransformerFilter(context, message, new XQJFilter(item));
		return new SAXSource(transformerFilter, null);
	}

	@Override
	protected XMLFilterBase createXMLFilter(Context context, ESBMessage message, XMLReader parent) throws TransformerException {
		return new TransformerFilter(context, message, parent);
	}

}
