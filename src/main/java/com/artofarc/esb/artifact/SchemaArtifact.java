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

import java.io.IOException;
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
import org.xml.sax.SAXException;

public abstract class SchemaArtifact extends Artifact implements LSResourceResolver, EntityResolver {

	protected static final String FILE_SCHEMA = "file://";

	protected Schema schema;

	protected JAXBContext _jaxbContext;

	protected SchemaArtifact(Directory parent, String name) {
		super(parent, name);
	}

	public Schema getSchema() {
		return schema;
	}

	public abstract JAXBContext getJAXBContext() throws JAXBException;

	protected SchemaFactory getSchemaFactory() {
		SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
		factory.setResourceResolver(this);
		return factory;
	}
	
	private URI lastUri;

	@Override
	public InputSource resolveEntity(String publicId, String systemId) throws SAXException, IOException {
		try {
			URI uri = new URI(systemId);
			XSDArtifact artifact = getArtifact(uri.getPath());
			if (artifact == null) {
				uri = lastUri.resolve(systemId);
				artifact = getArtifact(uri.getPath());
			}
			lastUri = uri;
			System.out.println(uri);
			InputSource is = new InputSource(artifact.getContentAsByteArrayInputStream());
			is.setSystemId(systemId);
			return is;
		} catch (URISyntaxException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public LSInput resolveResource(String type, String namespaceURI, String publicId, String systemId, String baseURI) {
		if (systemId.indexOf("//") >= 0) {
			throw new IllegalArgumentException("systemId must be a relative URI " + systemId);
		}
		SchemaArtifact base = this;
		if (baseURI != null) {
			if (!baseURI.startsWith(FILE_SCHEMA)) {
				throw new IllegalArgumentException("baseURI must start with " + FILE_SCHEMA);
			}
			base = getArtifact(baseURI.substring(FILE_SCHEMA.length()));
		}
		String resourceURI = base.getParent().getURI() + '/' + systemId;
		XSDArtifact artifact = getArtifact(resourceURI);
		if (baseURI == null) {
			baseURI = FILE_SCHEMA + artifact.getURI();
			systemId = artifact.getName();
		}
		base.addReference(artifact);
		return new LSInputImpl(publicId, systemId, baseURI, artifact.getContentAsByteArrayInputStream());
	}

	public static class LSInputImpl implements LSInput {
		private String publicId;
		private String systemId;
		private String baseURI;
		private InputStream byteStream;

		public LSInputImpl(String publicId, String systemId, String baseURI, InputStream byteStream) {
			this.publicId = publicId;
			this.systemId = systemId;
			this.baseURI = baseURI;
			this.byteStream = byteStream;
		}

		@Override
		public String getPublicId() {
			return publicId;
		}

		@Override
		public void setPublicId(String publicId) {
			this.publicId = publicId;
		}

		@Override
		public String getSystemId() {
			return systemId;
		}

		@Override
		public void setSystemId(String systemId) {
			this.systemId = systemId;
		}

		@Override
		public String getBaseURI() {
			return baseURI;
		}

		@Override
		public void setBaseURI(String baseURI) {
			this.baseURI = baseURI;
		}

		@Override
		public InputStream getByteStream() {
			return byteStream;
		}

		public void setByteStream(InputStream byteStream) {
			this.byteStream = byteStream;
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
