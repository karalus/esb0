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

import java.io.FileNotFoundException;
import java.io.IOException;

import javax.xml.bind.JAXBException;
import javax.xml.transform.stream.StreamSource;

import org.eclipse.persistence.jaxb.dynamic.DynamicJAXBContext;
import org.eclipse.persistence.jaxb.dynamic.DynamicJAXBContextFactory;

public class XSDArtifact extends SchemaArtifact {

	public XSDArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	@Override
	protected XSDArtifact clone(FileSystem fileSystem, Directory parent) {
		XSDArtifact clone = initClone(new XSDArtifact(fileSystem, parent, getName()));
		clone._jaxbContext = _jaxbContext;
		clone._schema = _schema;
		clone._grammars = _grammars;
		clone._namespace.set(getNamespace());
		return clone;
	}

	protected final StreamSource getStreamSource() {
		return new StreamSource(getContentAsStream(), getURI());
	}

	@Override
	public DynamicJAXBContext getJAXBContext() throws JAXBException, IOException {
		if (_jaxbContext == null) {
			_jaxbContext = DynamicJAXBContextFactory.createContextFromXSD(getStreamSource(), getResolver(), null, getDynamicJAXBContextProperties());
		}
		return _jaxbContext;
	}

	@Override
	protected XSDArtifact resolveArtifact(String systemId, String baseURI) throws FileNotFoundException {
		SchemaArtifact base = this;
		if (baseURI != null) {
			if (!baseURI.startsWith(FILE_SCHEMA)) {
				throw new IllegalArgumentException("baseURI must start with " + FILE_SCHEMA);
			}
			base = loadArtifact(baseURI.substring(FILE_SCHEMA.length()));
		}
		String resourceURI = base.getParent().getURI() + '/' + systemId;
		XSDArtifact artifact = loadArtifact(resourceURI);
		base.addReference(artifact);
		return artifact;
	}

	@Override
	protected StreamSource[] getSourcesForSchema() {
		return new StreamSource[] { getStreamSource() };
	}

	@Override
	public void clearContent() {
		if (!XMLCatalog.isXMLCatalog(getParent())) {
			super.clearContent();
		}
	}

}
