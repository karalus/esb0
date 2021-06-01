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

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.wsdl.Binding;
import javax.wsdl.Definition;
import javax.wsdl.Import;
import javax.wsdl.Types;
import javax.wsdl.extensions.schema.Schema;
import javax.wsdl.xml.WSDLLocator;
import javax.xml.namespace.QName;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMSource;

import org.w3c.dom.Element;
import org.w3c.dom.ls.LSInput;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.artofarc.util.JAXPFactoryHelper;
import com.artofarc.util.WSDL4JUtil;
import com.sun.xml.xsom.XSSchemaSet;
import com.sun.xml.xsom.parser.XSOMParser;

public class WSDLArtifact extends SchemaArtifact implements WSDLLocator {

	private volatile Map<QName, Binding> _allBindings;
	private final HashMap<String, byte[]> _schemas = new HashMap<>();
	// only used during validation
	private String latestImportURI;

	public WSDLArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	@Override
	protected WSDLArtifact clone(FileSystem fileSystem, Directory parent) {
		WSDLArtifact clone = initClone(new WSDLArtifact(fileSystem, parent, getName()));
		clone._allBindings = _allBindings;
		clone._schemaSet = _schemaSet;
		clone._schemas.putAll(_schemas);
		clone._schema = _schema;
		clone._grammars = _grammars;
		clone._namespace.set(getNamespace());
		return clone;
	}

	public final Map<QName, Binding> getAllBindings() throws ValidationException {
		// _allBindings might be set by different thread in {@link SchemaHelper}
		while (cacheXSGrammars && _namespace.get() != null && _allBindings == null) Thread.yield();
		if (_allBindings == null) {
			throw new ValidationException(this, "WSDL could not been parsed");
		}
		return _allBindings;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected Source[] getSourcesForSchema() throws Exception {
		Definition definition = WSDL4JUtil.createWSDLReader(false).readWSDL(this);
		_allBindings = definition.getAllBindings();
		_namespace.set(definition.getTargetNamespace());
		latestImportURI = null;
		Transformer transformer = JAXPFactoryHelper.newTransformer();
		List<Source> sources = new ArrayList<>();
		if (WSDL4JUtil.hasSOAP11Binding(_allBindings)) {
			XSDArtifact soap11 = loadArtifact(XMLCatalog.PATH + "/soap11.xsd");
			sources.add(soap11.getStreamSource());
			addReference(soap11);
		}
		if (WSDL4JUtil.hasSOAP12Binding(_allBindings)) {
			XSDArtifact soap12 = loadArtifact(XMLCatalog.PATH + "/soap12.xsd");
			sources.add(soap12.getStreamSource());
			addReference(soap12);
		}
		processSchemas(definition, sources, transformer);
		Map<String, List<Import>> importMap = definition.getImports();
		for (List<Import> imports : importMap.values()) {
			for (Import import1 : imports) {
				processSchemas(import1.getDefinition(), sources, transformer);
			}
		}
		return sources.toArray(new Source[sources.size()]);
	}

	private void processSchemas(Definition definition, List<Source> sources, Transformer transformer) throws TransformerException {
		Types types = definition.getTypes();
		if (types != null) {
			for (Schema schema : WSDL4JUtil.getExtensibilityElements(types, Schema.class)) {
				Element element = schema.getElement();
				String targetNamespace = element.getAttribute("targetNamespace");
				DOMSource schemaElement = new DOMSource(element, getURI());
				sources.add(schemaElement);
				_schemas.put(targetNamespace, XMLCatalog.toByteArray(schemaElement, transformer));
			}
		}
	}

	@Override
	public XSSchemaSet getXSSchemaSet() throws SAXException {
		if (_schemaSet == null) {
			XSOMParser xsomParser = new XSOMParser(JAXPFactoryHelper.getSAXParserFactory());
			xsomParser.setEntityResolver(getResolver());
			for (byte[] schemaContent : _schemas.values()) {
				InputSource is = new InputSource(new ByteArrayInputStream(schemaContent));
				is.setSystemId(getURI());
				xsomParser.parse(is);
			}
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
		return artifact;
	}

	@Override
	protected WSDLArtifactResourceResolver getResolver() {
		return new WSDLArtifactResourceResolver(this);
	}
	
	static class WSDLArtifactResourceResolver extends SchemaArtifactResolver {

		WSDLArtifactResourceResolver(WSDLArtifact wsdlArtifact) {
			super(wsdlArtifact);
		}

		@Override
		public LSInput resolveResource(String type, String namespaceURI, String publicId, String systemId, String baseURI) {
			if (systemId == null) {
				WSDLArtifact wsdlArtifact = (WSDLArtifact) _schemaArtifact.get();
				if (wsdlArtifact == null) {
					throw new IllegalStateException("Reference has already been cleared");
				}
				byte[] ba = wsdlArtifact._schemas.get(namespaceURI);
				if (ba != null) {
					return new LSInputImpl(publicId, null, baseURI, new ByteArrayInputStream(ba));
				}
			}
			return super.resolveResource(type, namespaceURI, publicId, systemId, baseURI);
		}
	}

	@Override
	public InputSource getBaseInputSource() {
		return getImportInputSource(null, getBaseURI());
	}

	@Override
	public String getBaseURI() {
		return getURI();
	}

	@Override
	public InputSource getImportInputSource(String parentLocation, String importLocation) {
		if (importLocation.contains("//")) {
			throw new IllegalArgumentException("importLocation must be a relative URI " + importLocation);
		}
		Artifact artifact;
		try {
			if (parentLocation != null) {
				Artifact parent = getArtifact(parentLocation);
				artifact = loadArtifact(latestImportURI = parent.getParent().getURI() + '/' + importLocation);
				parent.addReference(artifact);
			} else {
				artifact = loadArtifact(latestImportURI = importLocation);
			}
		} catch (FileNotFoundException e) {
			logger.error("Could not resolve InputSource", e);
			return null;
		}
		return new InputSource(artifact.getContentAsStream());
	}

	@Override
	public String getLatestImportURI() {
		return latestImportURI;
	}

	@Override
	public void close() {
	}

}
