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

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import javax.xml.bind.JAXBException;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.eclipse.persistence.jaxb.dynamic.DynamicJAXBContext;
import org.eclipse.persistence.jaxb.dynamic.DynamicJAXBContextFactory;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;

import com.artofarc.esb.context.GlobalContext;

public abstract class SchemaArtifact extends Artifact implements LSResourceResolver, EntityResolver {

	public static final String FILE_SCHEMA = "file://";

	private static final boolean cacheXSGrammars = Boolean.parseBoolean(System.getProperty("esb0.cacheXSGrammars"));
	private static final String JAXB_BINDINGS = System.getProperty("esb0.moxy.jaxb.bindings");

	protected static HashMap<String, Object> getDynamicJAXBContextProperties() throws IOException {
		if (JAXB_BINDINGS != null) {
			HashMap<String, Object> properties = new HashMap<>();
			properties.put(DynamicJAXBContextFactory.EXTERNAL_BINDINGS_KEY, new StreamSource(new FileInputStream(JAXB_BINDINGS), JAXB_BINDINGS));
			return properties;
		}
		return null;
	}

	protected HashMap<String, Object> _grammars = cacheXSGrammars ? new HashMap<String, Object>() : null;
	protected final AtomicReference<String> _namespace = new AtomicReference<>();
	protected Schema _schema;
	protected DynamicJAXBContext _jaxbContext;

	protected SchemaArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	public final String getNamespace() {
		return _namespace.get();
	}

	public final HashMap<String, Object> getGrammars() {
		return _grammars;
	}

	final Object putGrammarIfAbsent(String namespace, Object grammar) {
		Object old = _grammars.get(namespace);
		if (old == null) {
			_grammars.put(namespace, grammar);
		}
		return old;
	}

	@Override
	protected void invalidate() {
		if (cacheXSGrammars) {
			_grammars.clear();
			_namespace.set(null);
		}
		super.invalidate();
	}

	public final Schema getSchema() {
		return _schema;
	}

	public abstract DynamicJAXBContext getJAXBContext() throws JAXBException, IOException;

	protected final void initSchema(Source... schemas) throws Exception {
		if (cacheXSGrammars) {
			if (_namespace.compareAndSet(null, "dummy")) {
				_schema = SchemaHelper.createXMLSchema(this, schemas);
			}
		} else {
			SchemaFactory factory = SchemaFactory.newInstance(javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI);
			factory.setResourceResolver(this);
			_schema = factory.newSchema(schemas);
		}
	}

	@Override
	protected void postValidateInternal(GlobalContext globalContext) throws ValidationException {
		// refs have been set by resolvers, some refs can be added later because the way newSchema() works (depth first)
		// so make a copy of references for thread safety
		for (String artifactUri : new ArrayList<>(getReferenced())) {
			SchemaArtifact schemaArtifact = getArtifact(artifactUri);
			schemaArtifact.validate(globalContext);
		}
	}

	// only used during validation
	private URI lastUri;

	/**
	 * Used from MOXy.
	 */
	@Override
	public InputSource resolveEntity(String publicId, String systemId) {
		try {
			URI uri = new URI(systemId);
			XSDArtifact artifact = getArtifact(uri.getPath());
			if (artifact == null) {
				uri = lastUri.resolve(systemId);
				artifact = loadArtifact(uri.getPath());
			}
			lastUri = uri;
			InputSource is = new InputSource(artifact.getContentAsStream());
			is.setSystemId(systemId);
			return is;
		} catch (java.net.URISyntaxException | FileNotFoundException e) {
			throw new RuntimeException(e);
		}
	}

	protected final XSDArtifact resolveArtifact(String systemId, String baseURI) throws FileNotFoundException {
		SchemaArtifact base = this;
		if (baseURI != null) {
			if (!baseURI.startsWith(FILE_SCHEMA)) {
				throw new IllegalArgumentException("baseURI must start with " + FILE_SCHEMA);
			}
			base = getArtifact(baseURI.substring(FILE_SCHEMA.length()));
		}
		String resourceURI = base.getParent().getURI() + '/' + systemId;
		XSDArtifact artifact = loadArtifact(resourceURI);
		base.addReference(artifact);
		return artifact;
	}

	/**
	 * Used from Xerces XMLSchemaLoader.
	 */
	@Override
	public LSInput resolveResource(String type, String namespaceURI, String publicId, String systemId, String baseURI) {
		systemId = XMLCatalog.alignSystemId(systemId);
		try {
			XSDArtifact artifact = resolveArtifact(systemId, baseURI);
			artifact._namespace.set(namespaceURI);
			if (baseURI == null) {
				baseURI = FILE_SCHEMA + artifact.getURI();
				systemId = artifact.getName();
			}
			return new LSInputImpl(publicId, systemId, baseURI, artifact.getContentAsStream());
		} catch (FileNotFoundException e) {
			throw new IllegalArgumentException(e);
		}
	}

	public static class LSInputImpl implements LSInput {
		private String _publicId;
		private String _systemId;
		private String _baseURI;
		private InputStream _byteStream;

		public LSInputImpl(String publicId, String systemId, String baseURI, InputStream byteStream) {
			_publicId = publicId;
			_systemId = systemId;
			_baseURI = baseURI;
			_byteStream = byteStream;
		}

		@Override
		public String getPublicId() {
			return _publicId;
		}

		@Override
		public void setPublicId(String publicId) {
			_publicId = publicId;
		}

		@Override
		public String getSystemId() {
			return _systemId;
		}

		@Override
		public void setSystemId(String systemId) {
			_systemId = systemId;
		}

		@Override
		public String getBaseURI() {
			return _baseURI;
		}

		@Override
		public void setBaseURI(String baseURI) {
			_baseURI = baseURI;
		}

		@Override
		public InputStream getByteStream() {
			return _byteStream;
		}

		public void setByteStream(InputStream byteStream) {
			_byteStream = byteStream;
		}

		@Override
		public Reader getCharacterStream() {
			return null;
		}

		@Override
		public void setCharacterStream(Reader characterStream) {
		}

		@Override
		public String getStringData() {
			return null;
		}

		@Override
		public void setStringData(String stringData) {
		}

		@Override
		public String getEncoding() {
			return null;
		}

		@Override
		public void setEncoding(String encoding) {
		}

		@Override
		public boolean getCertifiedText() {
			return false;
		}

		@Override
		public void setCertifiedText(boolean certifiedText) {
		}

	}

}
