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
package com.artofarc.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.xpath.XPathFactory;

/**
 * In this millennium XML processors should be namespace aware by default.
 * We also want to cache factory lookup.
 */
public final class JAXPFactoryHelper {

	private static final boolean SECURE_PROCESSING = Boolean.parseBoolean(System.getProperty("esb0.jaxp.secure-processing", "true"));
	private static final SAXParserFactory SAX_PARSER_FACTORY = SAXParserFactory.newInstance();
	private static final SAXTransformerFactory SAX_TRANSFORMER_FACTORY;
	private static final MethodHandle conSAXTransformerFactory;
	private static final XPathFactory XPATH_FACTORY = XPathFactory.newInstance();

	static {
		try {
			SAX_PARSER_FACTORY.setNamespaceAware(true);
			SAX_PARSER_FACTORY.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, SECURE_PROCESSING);
			TransformerFactory transformerFactory = TransformerFactory.newInstance();
			if (transformerFactory.getFeature(SAXTransformerFactory.FEATURE)) {
				SAX_TRANSFORMER_FACTORY = (SAXTransformerFactory) transformerFactory;
				SAX_TRANSFORMER_FACTORY.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, SECURE_PROCESSING);
				conSAXTransformerFactory = MethodHandles.publicLookup().unreflectConstructor(SAX_TRANSFORMER_FACTORY.getClass().getConstructor());
			} else {
				throw new RuntimeException("Cannot be casted to SAXTransformerFactory: " + transformerFactory.getClass());
			}
		} catch (Exception e) {
			throw ReflectionUtils.convert(e, RuntimeException.class);
		}
	}

	public static SAXParserFactory getSAXParserFactory() {
		return SAX_PARSER_FACTORY;
	}

	public static XPathFactory getXPathFactory() {
		return XPATH_FACTORY;
	}

	public static Transformer newTransformer() throws TransformerConfigurationException {
		return SAX_TRANSFORMER_FACTORY.newTransformer();
	}

	public static TransformerHandler newTransformerHandler() throws TransformerConfigurationException {
		return SAX_TRANSFORMER_FACTORY.newTransformerHandler();
	}

	public static TransformerHandler newTransformerHandler(Templates templates) throws TransformerConfigurationException {
		return SAX_TRANSFORMER_FACTORY.newTransformerHandler(templates);
	}

	public static SAXTransformerFactory createSAXTransformerFactory() {
		try {
			SAXTransformerFactory saxTransformerFactory = (SAXTransformerFactory) conSAXTransformerFactory.invoke();
			saxTransformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, SECURE_PROCESSING);
			return saxTransformerFactory;
		} catch (Throwable e) {
			throw ReflectionUtils.convert(e, RuntimeException.class);
		}
	}

}
