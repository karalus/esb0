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
package com.artofarc.esb.artifact;

import java.util.Collection;
import java.util.List;

import javax.xml.transform.Templates;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamSource;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;

import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.util.DataStructures;
import com.artofarc.util.XMLProcessorFactory;

public class XSLTArtifact extends XMLProcessingArtifact {

	private List<String> _params;
	private volatile Templates _templates;

	public XSLTArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	@Override
	protected XSLTArtifact clone(FileSystem fileSystem, Directory parent) {
		XSLTArtifact clone = initClone(new XSLTArtifact(fileSystem, parent, getName()));
		clone._params = _params;
		return clone;
	}

	public final Templates getTemplates(GlobalContext globalContext) throws TransformerException {
		// double checked locking
		Templates templates = _templates;
		if (templates == null) {
			synchronized (this) {
				templates = _templates;
				if (templates == null) {
					templates = _templates = globalContext.getXMLProcessorFactory().newTemplates(new StreamSource(getContentAsStream(), getURI()));
					clearContent();
				}
			}
		}
		return templates;
	}

	public final List<String> getParams() {
		return _params;
	}

	@Override
	public void validateInternal(GlobalContext globalContext) throws Exception {
		// Needs an individual SAXTransformerFactory to track the use of imports/includes
		XMLProcessorFactory xmlProcessorFactory = XMLProcessorFactory.newInstance(new ArtifactURIResolver(this));
		ValidationErrorListener errorListener = new ValidationErrorListener(getURI());
		xmlProcessorFactory.setErrorListener(errorListener);
		try {
			xmlProcessorFactory.newTemplates(new StreamSource(getContentAsStream()));
		} catch (TransformerException e) {
			throw errorListener.build(e, -1);
		}
		// set imports/includes to validated 
		for (String referenced : getReferenced()) {
			getArtifact(referenced).setValidated();
		}
		// determine parameters
		XPath xPath = XMLProcessorFactory.getXPathFactory().newXPath();
		NodeList params = (NodeList) xPath.evaluate("*/*[local-name()='param']/@name", new InputSource(getContentAsStream()), XPathConstants.NODESET);
		_params = DataStructures.createList(params.getLength());
		for (int i = 0; i < params.getLength(); ++ i) {
			_params.add(params.item(i).getNodeValue());
		}
	}

	@Override
	protected void invalidate(Collection<Artifact> orphans) {
		_templates = null;
		_params = null;
		super.invalidate(orphans);
	}

}
