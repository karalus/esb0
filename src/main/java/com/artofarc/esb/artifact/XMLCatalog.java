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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
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
import com.artofarc.util.StreamUtils;

public final class XMLCatalog {

	public static final String PATH = "/xmlcatalog";

	public static final TransformerFactory TRANSFORMER_FACTORY = TransformerFactory.newInstance();

	public static void attachToFileSystem(GlobalContext globalContext) throws ValidationException, IOException {
		FileSystem fileSystem = globalContext.getFileSystem();
		Directory parent = fileSystem.makeDirectory(PATH.substring(1));
		try {
			XPath xPath = XPathFactory.newInstance().newXPath();
			Transformer transformer = TRANSFORMER_FACTORY.newTransformer();
			// Elements below Body must be strictly validated, with lax we don't detect some kind of errors
			// TODO: Works only with document/literal WSDL style and messageParts referring to elements
			String exp = "/*/*[local-name()='complexType' and @name='Body']/*/*/@processContents";
			{
				Attr attr = evaluate(xPath, exp, Artifact.getResourceAsStream("soap11.xsd"));
				attr.setValue("strict");
				XSDArtifact xsdArtifact = new XSDArtifact(fileSystem, parent, "soap11.xsd");
				xsdArtifact.setContent(toByteArray(new DOMSource(attr.getOwnerDocument()), transformer));
				xsdArtifact.validate(globalContext);
			}
			xPath.reset();
			{
				XSDArtifact xsdArtifact = new XSDArtifact(fileSystem, parent, "xml.xsd");
				xsdArtifact.setContent(StreamUtils.copy(Artifact.getResourceAsStream("xml.xsd")));
			}
			{
				Attr attr = evaluate(xPath, exp, Artifact.getResourceAsStream("soap12.xsd"));
				attr.setValue("strict");
				XSDArtifact xsdArtifact = new XSDArtifact(fileSystem, parent, "soap12.xsd");
				xsdArtifact.setContent(toByteArray(new DOMSource(attr.getOwnerDocument()), transformer));
				xsdArtifact.validate(globalContext);
			}
		} catch (XPathExpressionException | TransformerException e) {
			throw new RuntimeException(e);
		}
	}

	public static String alignSystemId(String systemId) {
		if (systemId != null && systemId.contains("//")) {
			// Must not download anything but search locally (XML catalog)
			systemId = systemId.substring(systemId.lastIndexOf('/') + 1);
		}
		return systemId;
	}

	public static byte[] toByteArray(DOMSource source, Transformer transformer) throws TransformerException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream(StreamUtils.MTU);
		transformer.transform(source, new StreamResult(bos));
		transformer.reset();
		return bos.toByteArray();
	}

	@SuppressWarnings("unchecked")
	public static <T extends Node> T evaluate(XPath xPath, String expression, InputStream is) throws XPathExpressionException {
		return (T) xPath.evaluate(expression, new InputSource(is), XPathConstants.NODE);
	}

}
