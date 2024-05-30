/*
 * Copyright 2022 Andre Karalus
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
package com.artofarc.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.ErrorListener;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.URIResolver;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.xpath.XPathFactory;
import javax.xml.xquery.XQConnection;
import javax.xml.xquery.XQException;
import javax.xml.xquery.XQItem;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class XMLProcessorFactory {

	public static final String XPATH_EXTENSION_NS_URI = "http://artofarc.com/xpath-extension";
	public static final String XPATH_EXTENSION_NS_PREFIX = "fn-artofarc";

	private static final boolean SECURE_PROCESSING = Boolean.parseBoolean(System.getProperty("esb0.jaxp.secure-processing", "true"));
	private static final boolean XQ2XSL_PROCESSING_SAX = Boolean.parseBoolean(System.getProperty("esb0.xq2xsl-processing.sax"));

	private static final DocumentBuilderFactory DOCUMENT_BUILDER_FACTORY = DocumentBuilderFactory.newInstance();
	private static final SAXParserFactory SAX_PARSER_FACTORY = SAXParserFactory.newInstance();
	private static final XPathFactory XPATH_FACTORY = XPathFactory.newInstance();
	private static final SAXTransformerFactory SAX_TRANSFORMER_FACTORY;
	private static final MethodHandle conSAXTransformerFactory;
	private static final MethodHandle conXMLProcessorFactory;

	static {
		try {
			// In this millennium XML processors should be namespace aware by default.
			DOCUMENT_BUILDER_FACTORY.setNamespaceAware(true);
			DOCUMENT_BUILDER_FACTORY.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, SECURE_PROCESSING);
			SAX_PARSER_FACTORY.setNamespaceAware(true);
			SAX_PARSER_FACTORY.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, SECURE_PROCESSING);
			TransformerFactory transformerFactory = TransformerFactory.newInstance();
			if (transformerFactory.getFeature(SAXTransformerFactory.FEATURE)) {
				SAX_TRANSFORMER_FACTORY = (SAXTransformerFactory) transformerFactory;
				conSAXTransformerFactory = MethodHandles.lookup().unreflectConstructor(transformerFactory.getClass().getConstructor());
			} else {
				throw new RuntimeException("Cannot be casted to SAXTransformerFactory: " + transformerFactory.getClass());
			}
			Class<?> xmlProcessorFactory;
			try {
				xmlProcessorFactory = Class.forName(System.getProperty("esb0.XMLProcessorFactory", "com.artofarc.util.saxon.SaxonXMLProcessorFactory"));
			} catch (ClassNotFoundException e) {
				xmlProcessorFactory = XMLProcessorFactory.class;
			}
			conXMLProcessorFactory = MethodHandles.lookup().unreflectConstructor(xmlProcessorFactory.getDeclaredConstructor(URIResolver.class));
		} catch (Exception e) {
			throw ReflectionUtils.convert(e, RuntimeException.class);
		}
	}

	public static DocumentBuilderFactory getDocumentBuilderFactory() {
		return DOCUMENT_BUILDER_FACTORY;
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

	public static XMLProcessorFactory newInstance(URIResolver uriResolver) {
		try {
			return (XMLProcessorFactory) conXMLProcessorFactory.invoke(uriResolver);
		} catch (Throwable e) {
			throw ReflectionUtils.convert(e, RuntimeException.class);
		}
	}

	protected final SAXTransformerFactory _saxTransformerFactory;

	protected XMLProcessorFactory(URIResolver uriResolver) throws Throwable {
		_saxTransformerFactory = (SAXTransformerFactory) conSAXTransformerFactory.invoke();
		_saxTransformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, SECURE_PROCESSING);
		_saxTransformerFactory.setURIResolver(uriResolver);
	}

	public final void setErrorListener(ErrorListener listener) {
		_saxTransformerFactory.setErrorListener(listener);
	}

	public final Templates newTemplates(Source source) throws TransformerConfigurationException {
		return _saxTransformerFactory.newTemplates(source);
	}

	public XQConnection getConnection() throws XQException {
		return null;
	}

	public static TransformerFilter createTransformerFilter(Templates templates, XQItem item) throws TransformerConfigurationException {
		return XQ2XSL_PROCESSING_SAX ? new SAXTransformerFilter(templates, item) : new DOMTransformerFilter(templates, item);
	}

	public abstract static class TransformerFilter extends XMLFilterBase {
		public abstract Transformer getTransformer();
	}

	private static class SAXTransformerFilter extends TransformerFilter {
		private final TransformerHandler _transformerHandler;
		private final XQItem _item;

		SAXTransformerFilter(Templates templates, XQItem item) throws TransformerConfigurationException {
			_transformerHandler = newTransformerHandler(templates);
			_item = item;
		}

		@Override
		public Transformer getTransformer() {
			return _transformerHandler.getTransformer();
		}

		@Override
		public void parse(InputSource source) throws SAXException {
			_transformerHandler.setResult(new SAXResult(getContentHandler()));
			try {
				_item.writeItemToResult(new SAXResult(_transformerHandler));
			} catch (XQException e) {
				throw new SAXException(e);
			}
		}
	}

	private static class DOMTransformerFilter extends TransformerFilter {
		private final Transformer _transformer;
		private final XQItem _item;

		DOMTransformerFilter(Templates templates, XQItem item) throws TransformerConfigurationException {
			_transformer = templates.newTransformer();
			_item = item;
		}

		@Override
		public Transformer getTransformer() {
			return _transformer;
		}

		@Override
		public void parse(InputSource source) throws SAXException {
			try {
				_transformer.transform(new DOMSource(_item.getNode()), new SAXResult(getContentHandler()));
			} catch (TransformerException | XQException e) {
				throw new SAXException(e);
			}
		}
	}

}
