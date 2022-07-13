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

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.xquery.XQItem;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import com.artofarc.esb.artifact.XSLTArtifact;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.Attachments2SAX;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.XMLFilterBase;
import com.artofarc.util.XMLProcessorFactory;

public class XSLTAction extends SAXAction {

	private final String _uri;
	private final List<String> _params;

	public XSLTAction(String uri, List<String> params) {
		_uri = uri;
		_params = params;
	}

	private Templates getTemplates(GlobalContext globalContext) throws TransformerException {
		XSLTArtifact xsltArtifact = globalContext.getFileSystem().getArtifact(_uri);
		return xsltArtifact.getTemplates(globalContext);
	}

	private void setParameters(Transformer transformer, ESBMessage message) {
		for (String param : _params) {
			if (param.equals("attachmentsHull")) {
				transformer.setParameter(param, new SAXSource(new Attachments2SAX(message, false), null));
			} else if (param.equals("attachments")) {
				transformer.setParameter(param, new SAXSource(new Attachments2SAX(message, true), null));
			} else {
				Object value = message.getVariable(param);
				if (value != null) {
					transformer.setParameter(param, value);
				}
			}
		}
		String mediaType = transformer.getOutputProperty(OutputKeys.MEDIA_TYPE);
		if (mediaType != null) {
			message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, mediaType);
		}
	}

	class TransformerFilter extends XMLFilterBase {
		final TransformerHandler transformerHandler;
		final Transformer transformer;

		TransformerFilter(Context context, ESBMessage message, XMLReader parent) throws TransformerException {
			if (parent != null) {
				super.setParent(parent);
				transformerHandler = XMLProcessorFactory.newTransformerHandler(getTemplates(context.getGlobalContext()));
				transformer = transformerHandler.getTransformer();
			} else {
				transformerHandler = null;
				transformer = getTemplates(context.getGlobalContext()).newTransformer();
			}
			setParameters(transformer, message);
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
		XMLProcessorFactory.TransformerFilter transformerFilter = XMLProcessorFactory.createTransformerFilter(getTemplates(context.getGlobalContext()), item);
		setParameters(transformerFilter.getTransformer(), message);
		return new SAXSource(transformerFilter, null);
	}

	@Override
	protected XMLFilterBase createXMLFilter(Context context, ESBMessage message, XMLReader parent) throws TransformerException {
		return new TransformerFilter(context, message, parent);
	}

}
