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
import java.util.HashMap;
import java.util.Map;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Attr;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.util.ByteArrayOutputStream;
import com.artofarc.util.IOUtils;
import com.artofarc.util.JAXPFactoryHelper;

public final class XMLCatalog extends Directory {

	public static final String PATH = "/xmlcatalog";

	private final Map<String, XSDArtifact> _schemaArtifacts = new HashMap<>();

	public XMLCatalog(FileSystem fileSystem) {
		super(fileSystem, fileSystem.getRoot(), PATH.substring(1));
	}

	public Map<String, XSDArtifact> getSchemaArtifacts() {
		return _schemaArtifacts;
	}

	private void addSchemaArtifact(String namespace, XSDArtifact artifact) {
		_schemaArtifacts.put(namespace, artifact);
		if (!SchemaArtifact.cacheXSGrammars) {
			artifact._namespace.set(namespace);
		}
	}

	@Override
	protected XMLCatalog clone(FileSystem fileSystem, Directory parent) {
		XMLCatalog clone = initClone(new XMLCatalog(fileSystem));
		for (Map.Entry<String, XSDArtifact> entry : clone._schemaArtifacts.entrySet()) {
			XSDArtifact childClone = entry.getValue().clone(fileSystem, clone);
			clone._schemaArtifacts.put(entry.getKey(), childClone);
			clone.getArtifacts().put(childClone.getName(), childClone);
		}
		return clone;
	}

	public static void attachToFileSystem(GlobalContext globalContext) {
		FileSystem fileSystem = globalContext.getFileSystem();
		XMLCatalog xmlCatalog = new XMLCatalog(fileSystem);
		try {
			{
				XSDArtifact xsdArtifact = new XSDArtifact(fileSystem, xmlCatalog, "xml.xsd");
				xsdArtifact.setContent(IOUtils.copy(IOUtils.getResourceAsStream("xml.xsd")));
				xmlCatalog.addSchemaArtifact("http://www.w3.org/XML/1998/namespace", xsdArtifact);
			}
			XPath xPath = XPathFactory.newInstance().newXPath();
			Transformer transformer = JAXPFactoryHelper.newTransformer();
			// Elements below Body must be strictly validated, with lax we don't detect some kind of errors
			// TODO: Works only with document/literal WSDL style and messageParts referring to elements
			String exp = "/*/*[local-name()='complexType' and @name='Body']/*/*/@processContents";
			{
				Attr attr = evaluate(xPath, exp, IOUtils.getResourceAsStream("soap11.xsd"));
				attr.setValue("strict");
				XSDArtifact xsdArtifact = new XSDArtifact(fileSystem, xmlCatalog, "soap11.xsd");
				xsdArtifact.setContent(toByteArray(new DOMSource(attr.getOwnerDocument()), transformer));
				xmlCatalog.addSchemaArtifact("http://schemas.xmlsoap.org/soap/envelope/", xsdArtifact);
			}
			xPath.reset();
			{
				Attr attr = evaluate(xPath, exp, IOUtils.getResourceAsStream("soap12.xsd"));
				attr.setValue("strict");
				XSDArtifact xsdArtifact = new XSDArtifact(fileSystem, xmlCatalog, "soap12.xsd");
				xsdArtifact.setContent(toByteArray(new DOMSource(attr.getOwnerDocument()), transformer));
				xmlCatalog.addSchemaArtifact("http://www.w3.org/2003/05/soap-envelope", xsdArtifact);
			}
			{
				XSDArtifact xsdArtifact = new XSDArtifact(fileSystem, xmlCatalog, "xop-include.xsd");
				xsdArtifact.setContent(IOUtils.copy(IOUtils.getResourceAsStream("xop-include.xsd")));
				xmlCatalog.addSchemaArtifact("http://www.w3.org/2004/08/xop/include", xsdArtifact);
			}
			{
				XSDArtifact xsdArtifact = new XSDArtifact(fileSystem, xmlCatalog, "xmlmime.xsd");
				xsdArtifact.setContent(IOUtils.copy(IOUtils.getResourceAsStream("xmlmime.xsd")));
				xmlCatalog.addSchemaArtifact("http://www.w3.org/2005/05/xmlmime", xsdArtifact);
			}
		} catch (IOException | XPathExpressionException | TransformerException e) {
			throw new RuntimeException(e);
		}
	}

	public static XSDArtifact get(Artifact artifact, String namespace) {
		XMLCatalog xmlCatalog = artifact.getArtifact(PATH);
		return xmlCatalog.getSchemaArtifacts().get(namespace);
	}

	public static boolean isXMLCatalog(Directory directory) {
		return directory.getURI().equals(PATH);
	}

	protected static byte[] toByteArray(DOMSource source, Transformer transformer) throws TransformerException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		transformer.transform(source, new StreamResult(bos));
		transformer.reset();
		return bos.toByteArray();
	}

	@SuppressWarnings("unchecked")
	public static <T extends Node> T evaluate(XPath xPath, String expression, InputStream is) throws XPathExpressionException {
		return (T) xPath.evaluate(expression, new InputSource(is), XPathConstants.NODE);
	}

}
