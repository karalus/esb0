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
import java.util.Properties;

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
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.Attachments2SAX;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.RichSource;
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
		Properties outputProperties = transformer.getOutputProperties();
		Properties props = message.getVariable(ESBConstants.serializationParameters);
		if (props != null) {
			props.putAll(outputProperties);
		} else {
			message.putVariable(ESBConstants.serializationParameters, outputProperties);
		}
		String mediaType = outputProperties.getProperty(OutputKeys.MEDIA_TYPE);
		if (mediaType != null) {
			message.setContentType(mediaType);
		} else {
			String method = outputProperties.getProperty(OutputKeys.METHOD);
			if (method == null || "xml".equals(method) || "xhtml".equals(method)) {
				message.setContentType(HttpConstants.HTTP_HEADER_CONTENT_TYPE_XML);
			} else{
				message.setContentType(HttpConstants.HTTP_HEADER_CONTENT_TYPE_TEXT);
			}
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
	protected RichSource createSource(Context context, ESBMessage message, XQItem item) throws Exception {
		XMLProcessorFactory.TransformerFilter transformerFilter = XMLProcessorFactory.createTransformerFilter(getTemplates(context.getGlobalContext()), item);
		setParameters(transformerFilter.getTransformer(), message);
		return new RichSource(new SAXSource(transformerFilter, null), item, null);
	}

	@Override
	protected XMLFilterBase createXMLFilter(Context context, ESBMessage message, XMLReader parent) throws TransformerException {
		return new TransformerFilter(context, message, parent);
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		message.preferXQItemBody();
		return super.prepare(context, message, inPipeline);
	}

}
