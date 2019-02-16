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

import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;

import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;

public abstract class SchemaArtifact extends Artifact implements LSResourceResolver, EntityResolver {

	protected static final String FILE_SCHEMA = "file://";

	protected Schema _schema;
	protected JAXBContext _jaxbContext;

	protected SchemaArtifact(Directory parent, String name) {
		super(parent, name);
	}

	public final Schema getSchema() {
		return _schema;
	}

	public abstract JAXBContext getJAXBContext() throws JAXBException;

	protected final SchemaFactory getSchemaFactory() {
		SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
		factory.setResourceResolver(this);
		return factory;
	}

	// only used during validation
	private URI lastUri;

	@Override
	public InputSource resolveEntity(String publicId, String systemId) {
		try {
			URI uri = new URI(systemId);
			XSDArtifact artifact = getArtifact(uri.getPath());
			if (artifact == null) {
				uri = lastUri.resolve(systemId);
				artifact = getArtifact(uri.getPath());
			}
			lastUri = uri;
			InputSource is = new InputSource(artifact.getContentAsStream());
			is.setSystemId(systemId);
			return is;
		} catch (URISyntaxException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public LSInput resolveResource(String type, String namespaceURI, String publicId, String systemId, String baseURI) {
		InputStream resourceAsStream = null;
		if (systemId.contains("//")) {
			// Must not download anything but search locally (XML catalog)
			String resourceName = systemId.substring(systemId.lastIndexOf('/') + 1);
			resourceAsStream = getResourceAsStream(resourceName);
		} else {
			SchemaArtifact base = this;
			if (baseURI != null) {
				if (!baseURI.startsWith(FILE_SCHEMA)) {
					throw new IllegalArgumentException("baseURI must start with " + FILE_SCHEMA);
				}
				base = getArtifact(baseURI.substring(FILE_SCHEMA.length()));
			}
			String resourceURI = base.getParent().getURI() + '/' + systemId;
			XSDArtifact artifact = getArtifact(resourceURI);
			if (artifact != null) {
				if (baseURI == null) {
					baseURI = FILE_SCHEMA + artifact.getURI();
					systemId = artifact.getName();
				}
				base.addReference(artifact);
				resourceAsStream = artifact.getContentAsStream();
			}
		}
		if (resourceAsStream == null) {
			throw new IllegalArgumentException("cannot resolve " + systemId);
		}
		return new LSInputImpl(publicId, systemId, baseURI, resourceAsStream);
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
