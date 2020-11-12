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
package com.artofarc.esb.artifact;

import java.util.ArrayList;
import java.util.List;

import javax.xml.transform.Templates;
import javax.xml.transform.TransformerException;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;

import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.util.JAXPFactoryHelper;

public class XSLTArtifact extends XMLProcessingArtifact {

	private Templates _templates;
	private List<String> _params;

	public XSLTArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	@Override
	protected XSLTArtifact clone(FileSystem fileSystem, Directory parent) {
		XSLTArtifact clone = initClone(new XSLTArtifact(fileSystem, parent, getName()));
		clone._templates = _templates;
		clone._params = _params;
		return clone;
	}

	public final Templates getTemplates() {
		return _templates;
	}

	public final List<String> getParams() {
		return _params;
	}

	@Override
	public void validateInternal(GlobalContext globalContext) throws TransformerException, XPathExpressionException {
		// Needs an individual SAXTransformerFactory to track the use of imports/includes
		SAXTransformerFactory saxTransformerFactory = JAXPFactoryHelper.createSAXTransformerFactory();
		ValidationErrorListener errorListener = new ValidationErrorListener(getURI());
		saxTransformerFactory.setErrorListener(errorListener);
		saxTransformerFactory.setURIResolver(new ArtifactURIResolver(this));
		try {
			_templates = saxTransformerFactory.newTemplates(new StreamSource(getContentAsStream()));
		} catch (TransformerException e) {
			if (errorListener.exceptions.isEmpty()) {
				throw e;
			} else {
				throw errorListener.exceptions.get(0);
			}
		}
		saxTransformerFactory.setURIResolver(null);
		// set imports/includes to validated 
		for (String referenced : getReferenced()) {
			getArtifact(referenced).setValidated();
		}
		// determine parameters
		XPath xPath = JAXPFactoryHelper.getXPathFactory().newXPath();
		NodeList params = (NodeList) xPath.evaluate("*/*[local-name()='param']/@name", new InputSource(getContentAsStream()), XPathConstants.NODESET);
		_params = new ArrayList<>();
		for (int i = 0; i < params.getLength(); ++ i) {
			_params.add(params.item(i).getNodeValue());
		}
	}

	@Override
	protected void invalidate() {
		_templates = null;
		_params = null;
		super.invalidate();
	}

}
