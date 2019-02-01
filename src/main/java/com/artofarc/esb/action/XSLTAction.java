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

public class XSLTAction extends SAXAction {

	private final Templates _templates;

	public XSLTAction(Templates templates) {
		_templates = templates;
	}

	class TransformerFilter extends FeatureFilter {

		private final TransformerHandler transformerHandler;
		private final Transformer transformer;

		TransformerFilter(Context context, XMLReader parent) throws TransformerConfigurationException {
			if (parent != null) {
				super.setParent(parent);
				transformerHandler = Context.getSAXTransformerFactory().newTransformerHandler(_templates);
				transformer = transformerHandler.getTransformer();
			} else {
				transformerHandler = null;
				transformer = _templates.newTransformer();
			}
			transformer.setURIResolver(context.getPoolContext().getGlobalContext().getUriResolver());
		}

		@Override
		public void setParent(XMLReader parent) {
			if (parent == null ^ getParent() == null) {
				throw new IllegalStateException("having a parent cannot be changed after construction");
			}
		}

		@Override
		public void parse(InputSource source) throws IOException, SAXException {
			if (transformerHandler != null) {
				transformerHandler.setResult(new SAXResult(getContentHandler()));
				getParent().setContentHandler(transformerHandler);
				getParent().parse(source);
			} else {
				try {
					transformer.transform(InputSourceToSource(source), new SAXResult(getContentHandler()));
				} catch (TransformerException e) {
					throw new SAXException(e);
				}
			}
		}

	}

	private void apply(ESBMessage message, Transformer transformer) {
		for (Entry<String, Object> variable : message.getVariables().entrySet()) {
			transformer.setParameter(variable.getKey(), variable.getValue());
		}
		transformer.setOutputProperties(message.getSinkProperties());
	}

	protected SAXSource createSAXSource(XQItem item, Context context, ESBMessage message) throws TransformerConfigurationException {
		TransformerFilter transformerFilter = new TransformerFilter(context, new XQJFilter(item));
		apply(message, transformerFilter.transformer);
		return new SAXSource(transformerFilter, null);
	}

	protected FeatureFilter createXMLFilter(XMLReader parent, Context context, ESBMessage message) throws TransformerConfigurationException {
		TransformerFilter transformerFilter = new TransformerFilter(context, parent);
		apply(message, transformerFilter.transformer);
		return transformerFilter;
	}

}
