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
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;

import javax.wsdl.Definition;
import javax.wsdl.extensions.schema.Schema;
import javax.wsdl.xml.WSDLLocator;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Element;
import org.w3c.dom.ls.LSInput;
import org.xml.sax.InputSource;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.util.WSDL4JUtil;

public class WSDLArtifact extends SchemaArtifact implements WSDLLocator {

	private static final TransformerFactory TRANSFORMER_FACTORY = TransformerFactory.newInstance();

	private Definition _definition;

	// only used during validation
	private String _latestImportURI;

	private final HashMap<String, byte[]> _schemas = new HashMap<>();

	public WSDLArtifact(Directory parent, String name) {
		super(parent, name);
	}

	@Override
	public WSDLArtifact clone(Directory parent) {
		WSDLArtifact clone = initClone(new WSDLArtifact(parent, getName()));
		// clone._definition = _definition;
		return clone;
	}

	public Definition getDefinition() {
		return _definition;
	}

	@Override
	protected void validateInternal(GlobalContext globalContext) throws Exception {
		_definition = WSDL4JUtil.createWSDLReader(false).readWSDL(this);
		List<Schema> schemas = WSDL4JUtil.getExtensibilityElements(_definition.getTypes(), Schema.class);
		DOMSource[] sources = new DOMSource[schemas.size()];
		Transformer transformer = TRANSFORMER_FACTORY.newTransformer();
		for (int i = 0; i < sources.length; ++i) {
			Element element = schemas.get(i).getElement();
			String targetNamespace = element.getAttribute("targetNamespace");
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			sources[i] = new DOMSource(element);
			transformer.transform(sources[i], new StreamResult(bos));
			_schemas.put(targetNamespace, bos.toByteArray());
		}
		schema = getSchemaFactory().newSchema(sources);
		_schemas.clear();
		// refs are now set
		validateReferenced(globalContext);
	}

	@Override
	public LSInput resolveResource(String type, String namespaceURI, String publicId, String systemId, String baseURI) {
		if (systemId == null) {
			return new LSInputImpl(publicId, systemId, baseURI, new ByteArrayInputStream(_schemas.get(namespaceURI)));
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
		if (importLocation.indexOf("//") >= 0) {
			throw new IllegalArgumentException("importLocation must be a relative URI " + importLocation);
		}
		Artifact artifact;
		if (parentLocation != null) {
			Artifact parent = getArtifact(parentLocation);
			artifact = getArtifact(_latestImportURI = parent.getParent().getURI() + '/' + importLocation);
			parent.addReference(artifact);
		} else {
			artifact = getArtifact(_latestImportURI = importLocation);
		}
		return new InputSource(artifact.getContentAsByteArrayInputStream());
	}

	@Override
	public String getLatestImportURI() {
		return _latestImportURI;
	}

	@Override
	public void close() {
	}

}
