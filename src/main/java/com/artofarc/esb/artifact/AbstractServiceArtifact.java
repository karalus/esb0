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

import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.xml.sax.SAXException;

import com.artofarc.esb.service.ObjectFactory;

public abstract class AbstractServiceArtifact extends Artifact {

	private final static JAXBContext jaxbContext;
	private final static Schema schema;

	static {
		try {
			jaxbContext = JAXBContext.newInstance(ObjectFactory.class);
			logger.info("JAXBContext: " + jaxbContext.getClass().getName());
		} catch (JAXBException e) {
			throw new RuntimeException("Cannot initialize JAXBContext", e);
		}
		SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
		try (InputStream inputStream = getResourceAsStream("service.xsd")) {
			schema = factory.newSchema(new StreamSource(inputStream));
		} catch (SAXException | IOException e) {
			throw new RuntimeException("Cannot parse service schema", e);
		}
	}

	public AbstractServiceArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	@SuppressWarnings("unchecked")
	protected final <S> S unmarshal() throws JAXBException {
		Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
		unmarshaller.setSchema(schema);
		return (S) unmarshaller.unmarshal(getContentAsStream());
	}

}
