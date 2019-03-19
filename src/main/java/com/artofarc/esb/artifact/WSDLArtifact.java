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
import javax.xml.bind.JAXBException;
import javax.xml.namespace.QName;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMSource;

import org.eclipse.persistence.jaxb.dynamic.DynamicJAXBContext;
import org.eclipse.persistence.jaxb.dynamic.DynamicJAXBContextFactory;
import org.w3c.dom.Element;
import org.w3c.dom.ls.LSInput;
import org.xml.sax.InputSource;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.util.WSDL4JUtil;

public class WSDLArtifact extends SchemaArtifact implements WSDLLocator {

	private Map<QName, Binding> _allBindings;
	private DOMSource _lastSchemaElement;
	// only used during validation
	private String latestImportURI;
	private final HashMap<String, byte[]> schemas = new HashMap<>();

	public WSDLArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	@Override
	protected WSDLArtifact clone(FileSystem fileSystem, Directory parent) {
		WSDLArtifact clone = initClone(new WSDLArtifact(fileSystem, parent, getName()));
		clone._allBindings = _allBindings;
		clone._jaxbContext = _jaxbContext;
		clone._lastSchemaElement = _lastSchemaElement;
		clone._schema = _schema;
		clone._grammars = _grammars;
		return clone;
	}

	public final Map<QName, Binding> getAllBindings() {
		return _allBindings;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void validateInternal(GlobalContext globalContext) throws Exception {
		Definition definition = WSDL4JUtil.createWSDLReader(false).readWSDL(this);
		_allBindings = definition.getAllBindings();
		latestImportURI = null;
		Transformer transformer = XMLCatalog.TRANSFORMER_FACTORY.newTransformer();
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
		initSchema(sources.toArray(new Source[sources.size()]));
		schemas.clear();
	}

	private void processSchemas(Definition definition, List<Source> sources, Transformer transformer) throws TransformerException {
		Types types = definition.getTypes();
		if (types != null) {
			for (Schema schema : WSDL4JUtil.getExtensibilityElements(types, Schema.class)) {
				Element element = schema.getElement();
				String targetNamespace = element.getAttribute("targetNamespace");
				_lastSchemaElement = new DOMSource(element);
				_lastSchemaElement.setSystemId(getURI());
				sources.add(_lastSchemaElement);
				schemas.put(targetNamespace, XMLCatalog.toByteArray(_lastSchemaElement, transformer));
			}
		}
	}

	@Override
	public DynamicJAXBContext getJAXBContext() throws JAXBException {
		if (_jaxbContext == null && _lastSchemaElement != null) {
			// TODO: This just works when the WSDL contains the one schema with the elements used in messages
			_jaxbContext = DynamicJAXBContextFactory.createContextFromXSD(_lastSchemaElement, this, null, null);
			_lastSchemaElement = null;
		}
		return _jaxbContext;
	}

	@Override
	public LSInput resolveResource(String type, String namespaceURI, String publicId, String systemId, String baseURI) {
		if (systemId == null) {
			return new LSInputImpl(publicId, null, baseURI, new ByteArrayInputStream(schemas.get(namespaceURI)));
		} else {
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
		if (parentLocation != null) {
			Artifact parent = getArtifact(parentLocation);
			artifact = getArtifact(latestImportURI = parent.getParent().getURI() + '/' + importLocation);
			parent.addReference(artifact);
		} else {
			artifact = getArtifact(latestImportURI = importLocation);
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
