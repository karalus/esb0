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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.zip.CRC32;

import javax.naming.NamingException;
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
import com.artofarc.esb.service.Property;
import com.artofarc.util.IOUtils;
import com.artofarc.util.XMLProcessorFactory;

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
				migrationXSLT = XMLProcessorFactory.newInstance(null).newTemplates(new StreamSource(inputStream));
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
	protected final <S> S unmarshal(GlobalContext globalContext) throws TransformerException, JAXBException {
		if (migrationXSLT != null) {
			migrate(globalContext);
		}
		Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
		unmarshaller.setSchema(schema);
		return (S) unmarshaller.unmarshal(getContentAsStream());
	}

	protected static Properties createProperties(List<Property> propertyList, GlobalContext globalContext) throws NamingException {
		Properties properties = new Properties();
		for (Property property : propertyList) {
			properties.put(property.getKey(), globalContext.bindProperties(property.getValue()));
		}
		return properties;
	}

	protected final ClassLoader resolveClassLoader(GlobalContext globalContext, String classLoaderURI) throws Exception {
		if (classLoaderURI != null) {
			ClassLoaderArtifact classLoaderArtifact = loadArtifact(classLoaderURI + '.' + ClassLoaderArtifact.FILE_EXTENSION);
			addReference(classLoaderArtifact);
			classLoaderArtifact.validate(globalContext);
			return classLoaderArtifact.getFileSystemClassLoader();
		}
		return globalContext.getClassLoader();
	}

	private void migrate(GlobalContext globalContext) throws TransformerException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		byte[] content1 = transform(XMLProcessorFactory.newTransformer(), bos);
		// content1 could be overwritten when using com.artofarc.util.ByteArrayOutputStream
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

	private byte[] transform(Transformer transformer, ByteArrayOutputStream bos) throws TransformerException {
		transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
		transformer.setParameter("ext", IOUtils.getExt(getName()));
		transformer.transform(new StreamSource(getContentAsStream()), new StreamResult(bos));
		return bos.toByteArray();
	}

}
