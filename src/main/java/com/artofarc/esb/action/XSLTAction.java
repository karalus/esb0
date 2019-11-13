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
package com.artofarc.esb.action;

import java.io.IOException;
import java.util.Map.Entry;

import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.xquery.XQItem;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.XMLFilterBase;
import com.artofarc.util.SAXTransformerFactoryHelper;

public class XSLTAction extends SAXAction {

	private final Templates _templates;

	public XSLTAction(Templates templates) {
		_templates = templates;
	}

	class TransformerFilter extends XMLFilterBase {
		final TransformerHandler transformerHandler;
		final Transformer transformer;

		TransformerFilter(Context context, ESBMessage message, XMLReader parent) throws TransformerConfigurationException {
			if (parent != null) {
				super.setParent(parent);
				transformerHandler = SAXTransformerFactoryHelper.newTransformerHandler(_templates);
				transformer = transformerHandler.getTransformer();
			} else {
				transformerHandler = null;
				transformer = _templates.newTransformer();
			}
			transformer.setURIResolver(context.getPoolContext().getGlobalContext().getURIResolver());
			for (Entry<String, Object> variable : message.getVariables().entrySet()) {
				transformer.setParameter(variable.getKey(), variable.getValue());
			}
			transformer.setOutputProperties(message.getSinkProperties());
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
	protected SAXSource createSAXSource(Context context, ESBMessage message, XQItem item) throws TransformerConfigurationException {
		TransformerFilter transformerFilter = new TransformerFilter(context, message, new XQJFilter(item));
		return new SAXSource(transformerFilter, null);
	}

	@Override
	protected XMLFilterBase createXMLFilter(Context context, ESBMessage message, XMLReader parent) throws TransformerConfigurationException {
		return new TransformerFilter(context, message, parent);
	}

}
