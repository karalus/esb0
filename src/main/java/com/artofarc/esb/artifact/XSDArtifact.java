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

import java.io.FileNotFoundException;

import javax.xml.transform.stream.StreamSource;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.artofarc.util.XMLProcessorFactory;
import com.sun.xml.xsom.XSSchemaSet;
import com.sun.xml.xsom.parser.XSOMParser;

public class XSDArtifact extends SchemaArtifact {

	public XSDArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	@Override
	protected XSDArtifact clone(FileSystem fileSystem, Directory parent) {
		XSDArtifact clone = initClone(new XSDArtifact(fileSystem, parent, getName()));
		clone._schemaSet = _schemaSet;
		clone._schema = _schema;
		clone._grammars = _grammars;
		clone._namespace.set(getNamespace());
		return clone;
	}

	protected final StreamSource getStreamSource() {
		return new StreamSource(getContentAsStream(), getURI());
	}

	@Override
	public XSSchemaSet getXSSchemaSet() throws SAXException {
		if (_schemaSet == null) {
			XSOMParser xsomParser = new XSOMParser(XMLProcessorFactory.getSAXParserFactory());
			xsomParser.setEntityResolver(getResolver());
			InputSource is = new InputSource(getContentAsStream());
			is.setSystemId(getURI());
			xsomParser.parse(is);
			_schemaSet = xsomParser.getResult();
		}
		return _schemaSet;
	}

	@Override
	protected XSDArtifact resolveArtifact(String namespaceURI, String systemId, String baseURI) throws FileNotFoundException {
		SchemaArtifact base = this;
		if (baseURI != null) {
			base = loadArtifact(getPathFromFileURI(baseURI));
		}
		XSDArtifact artifact = XMLCatalog.get(base, namespaceURI);
		if (artifact == null) {
			String resourceURI = base.getParent().getURI() + '/' + systemId;
			artifact = loadArtifact(resourceURI);
		}
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
