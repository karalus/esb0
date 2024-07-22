/*
 * Copyright 2021 Andre Karalus
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
import java.io.InputStream;
import java.io.Reader;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.xml.transform.Source;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.artofarc.esb.context.GlobalContext;
import com.sun.xml.xsom.XSSchemaSet;

public abstract class SchemaArtifact extends Artifact {

	private static final String FILE_SCHEMA = "file://";

	protected static final boolean cacheXSGrammars = Boolean.parseBoolean(System.getProperty("esb0.cacheXSGrammars", "true"));

	protected Map<String, Object> _grammars = cacheXSGrammars ? new HashMap<>() : null;
	protected final AtomicReference<String> _namespace = new AtomicReference<>();
	protected volatile Schema _schema;
	protected XSSchemaSet _schemaSet;

	protected SchemaArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	public final String getNamespace() {
		return _namespace.get();
	}

	public final Map<String, Object> getGrammars() {
		return _grammars;
	}

	@Override
	protected void invalidate() {
		if (cacheXSGrammars) {
			_grammars.clear();
			_namespace.set(null);
		}
		_schemaSet = null;
		super.invalidate();
	}

	public final Schema getSchema() {
		// _schema might be set by different thread in {@link SchemaHelper}
		while (cacheXSGrammars && _namespace.get() != null && _schema == null) Thread.yield();
		return _schema;
	}

	protected abstract Source[] getSourcesForSchema() throws Exception;

	final void createXMLSchema() throws Exception {
		try {
			_schema = SchemaHelper.createXMLSchema(this, getSourcesForSchema());
		} catch (Exception e) {
			_namespace.set(null);
			throw e;
		}
	}

	@Override
	public void validateInternal(GlobalContext globalContext) throws Exception {
		if (cacheXSGrammars) {
			if (_namespace.compareAndSet(null, "dummy")) {
				createXMLSchema();
			}
		} else {
			SchemaFactory factory = SchemaFactory.newInstance(javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI);
			factory.setResourceResolver(getResolver());
			// https://xerces.apache.org/xerces2-j/javadocs/xerces2/org/apache/xerces/impl/xs/XMLSchemaValidator.html
			factory.setFeature("http://apache.org/xml/features/namespace-growth", true);
			factory.setFeature("http://apache.org/xml/features/internal/tolerate-duplicates", true);
			_schema = factory.newSchema(getSourcesForSchema());
		}
	}

	public abstract XSSchemaSet getXSSchemaSet() throws SAXException;

	@Override
	protected void postValidateInternal(GlobalContext globalContext) throws ValidationException {
		// refs have been set by resolvers, some refs can be added later because the way newSchema() works (depth first)
		// so make a copy of references for thread safety
		for (String artifactUri : new ArrayList<>(getReferenced())) {
			SchemaArtifact schemaArtifact = getArtifact(artifactUri);
			schemaArtifact.validate(globalContext);
		}
	}

	protected static String getPathFromFileURI(String uriString) {
		if (uriString.indexOf(':') >= 0) {
			URI uri = URI.create(uriString);
			if (!FILE_SCHEMA.startsWith(uri.getScheme())) {
				throw new IllegalArgumentException("uri must have file scheme " + uriString);
			}
			return uri.getPath();
		}
		return uriString;
	}

	protected abstract XSDArtifact resolveArtifact(String namespaceURI, String systemId, String baseURI) throws FileNotFoundException;

	protected SchemaArtifactResolver getResolver() {
		return new SchemaArtifactResolver(this);
	}

	static class SchemaArtifactResolver implements EntityResolver, LSResourceResolver {

		final WeakReference<SchemaArtifact> _schemaArtifact;

		private URI lastUri;

		SchemaArtifactResolver(SchemaArtifact schemaArtifact) {
			_schemaArtifact = new WeakReference<>(schemaArtifact);
		}

		/**
		 * Used from {@link #getXSSchemaSet}
		 */
		@Override
		public InputSource resolveEntity(String publicId, String systemId) {
			SchemaArtifact schemaArtifact = _schemaArtifact.get();
			if (schemaArtifact == null) {
				throw new IllegalStateException("Reference has already been cleared");
			}
			try {
				URI uri = URI.create(systemId);
				XSDArtifact artifact;
				if (FILE_SCHEMA.startsWith(uri.getScheme())) {
					artifact = schemaArtifact.getArtifact(uri.getPath());
					if (artifact == null) {
						uri = lastUri.resolve(uri);
						artifact = schemaArtifact.loadArtifact(uri.getPath());
					}
					lastUri = uri;
				} else {
					artifact = XMLCatalog.get(schemaArtifact, publicId);
				}
				InputSource is = new InputSource(artifact.getContentAsStream());
				is.setSystemId(systemId);
				return is;
			} catch (FileNotFoundException e) {
				throw new RuntimeException(e);
			}
		}

		/**
		 * Used from {@link SchemaFactory}
		 */
		@Override
		public LSInput resolveResource(String type, String namespaceURI, String publicId, String systemId, String baseURI) {
			SchemaArtifact schemaArtifact = _schemaArtifact.get();
			if (schemaArtifact == null) {
				throw new IllegalStateException("Reference has already been cleared");
			}
			try {
				XSDArtifact artifact = schemaArtifact.resolveArtifact(namespaceURI, systemId, baseURI);
				if (!cacheXSGrammars) {
					artifact._namespace.set(namespaceURI);
				}
				if (baseURI == null || !baseURI.startsWith(FILE_SCHEMA)) {
					baseURI = FILE_SCHEMA + artifact.getURI();
					systemId = artifact.getName();
				}
				return new LSInputImpl(publicId, systemId, baseURI, artifact.getContentAsStream());
			} catch (FileNotFoundException e) {
				throw new IllegalArgumentException(e);
			}
		}
	}

	static class LSInputImpl implements LSInput {
		private String _publicId;
		private String _systemId;
		private String _baseURI;
		private InputStream _byteStream;

		LSInputImpl(String publicId, String systemId, String baseURI, InputStream byteStream) {
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
