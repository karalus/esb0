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
import java.util.Arrays;
import java.util.zip.CRC32;

import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.xml.sax.SAXException;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.util.ByteArrayOutputStream;
import com.artofarc.util.JAXPFactoryHelper;

public abstract class AbstractServiceArtifact extends Artifact {

	private final static JAXBContext jaxbContext;
	private final static Schema schema;
	private final static Templates migrationXSLT;

	static {
		try {
			jaxbContext = JAXBContext.newInstance(com.artofarc.esb.service.ObjectFactory.class);
		} catch (JAXBException e) {
			throw new RuntimeException("Cannot initialize JAXBContext", e);
		}
		ClassLoader classLoader = AbstractServiceArtifact.class.getClassLoader();
		SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
		try (InputStream inputStream = classLoader.getResourceAsStream("service.xsd")) {
			schema = factory.newSchema(new StreamSource(inputStream));
		} catch (SAXException | IOException e) {
			throw new RuntimeException("Cannot parse service schema", e);
		}
		try (InputStream inputStream = classLoader.getResourceAsStream("migrate.xsl")) {
			if (inputStream != null) {
				migrationXSLT = JAXPFactoryHelper.createSAXTransformerFactory().newTemplates(new StreamSource(inputStream));
				logger.info("Found migration XSLT");
			} else {
				migrationXSLT = null;
			}
		} catch (TransformerException | IOException e) {
			throw new RuntimeException(e);
		}
	}

	protected AbstractServiceArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	@SuppressWarnings("unchecked")
	protected final <S> S unmarshal() throws JAXBException {
		Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
		unmarshaller.setSchema(schema);
		return (S) unmarshaller.unmarshal(getContentAsStream());
	}

	protected final void migrate(GlobalContext globalContext) throws TransformerException {
		if (migrationXSLT != null) {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			byte[] content1 = transform(JAXPFactoryHelper.newTransformer(), bos);
			bos.reset();
			byte[] content2 = transform(migrationXSLT.newTransformer(), bos);
			if (!Arrays.equals(content1, content2)) {
				CRC32 crc = new CRC32();
				crc.update(content2);
				setContent(content2);
				setModificationTime(System.currentTimeMillis());
				setCrc(crc.getValue());
				noteChange();
				logger.info("Migrated artifact " + getURI());
			}
		}
	}

	private final byte[] transform(Transformer transformer, ByteArrayOutputStream bos) throws TransformerException {
		transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
		transformer.transform(new StreamSource(getContentAsStream()), new StreamResult(bos));
		return bos.toByteArray();
	}

}
