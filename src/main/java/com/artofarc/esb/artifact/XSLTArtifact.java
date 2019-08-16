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

import javax.xml.transform.Templates;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.stream.StreamSource;

import com.artofarc.esb.context.GlobalContext;

public class XSLTArtifact extends XMLArtifact {

	private Templates _templates;

	public XSLTArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	@Override
	protected XSLTArtifact clone(FileSystem fileSystem, Directory parent) {
		XSLTArtifact clone = initClone(new XSLTArtifact(fileSystem, parent, getName()));
		clone._templates = _templates;
		return clone;
	}

	public final Templates getTemplates() {
		return _templates;
	}

	@Override
	public void validateInternal(GlobalContext globalContext) throws TransformerConfigurationException {
		// Needs an individual SAXTransformerFactory to track the use of imports/includes
		SAXTransformerFactory saxTransformerFactory = (SAXTransformerFactory) SAXTransformerFactory.newInstance();
		saxTransformerFactory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
		saxTransformerFactory.setURIResolver(getURIResolver());
		_templates = saxTransformerFactory.newTemplates(new StreamSource(getContentAsStream()));
		// set imports/includes to validated 
		for (String referenced : getReferenced()) {
			getArtifact(referenced).setValidated();
		}
	}

}
