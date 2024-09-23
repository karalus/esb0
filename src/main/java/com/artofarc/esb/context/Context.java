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
package com.artofarc.esb.context;

import java.io.OutputStream;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xquery.XQConnection;
import javax.xml.xquery.XQDataFactory;
import javax.xml.xquery.XQException;
import javax.xml.xquery.XQItemAccessor;
import javax.xml.xquery.XQPreparedExpression;
import javax.xml.xquery.XQSequence;

import org.jvnet.fastinfoset.sax.FastInfosetReader;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import com.artofarc.esb.action.Action;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.util.NamespaceBeautifier;
import com.artofarc.util.TimeGauge;
import com.artofarc.util.XMLProcessorFactory;
import com.artofarc.util.XQuerySource;

public final class Context extends AbstractContext {

	public static final String XML_OUTPUT_INDENT = System.getProperty("esb0.xmlOutputIndent", "yes");

	private final PoolContext _poolContext;
	private final Transformer _transformer;
	private final XQConnection _xqConnection;
	private final XQSequence _xqSequence;
	private final Map<XQuerySource, XQPreparedExpression> _mapXQ = new HashMap<>();
	private final TimeGauge _timeGauge = new TimeGauge(logger);
	private final Deque<Action> _executionStack = new ArrayDeque<>();
	private final Deque<Action> _stackErrorHandler = new ArrayDeque<>();
	private final Deque<Integer> _stackPos = new ArrayDeque<>();
	private final Map<String, Object> _resources = new HashMap<>();

	private SAXParser _saxParser;
	private FastInfosetReader _fastInfosetDeserializer;

	public Context(PoolContext poolContext) {
		_poolContext = poolContext;
		try {
			_transformer = XMLProcessorFactory.newTransformer();
		} catch (TransformerConfigurationException e) {
			throw new RuntimeException(e);
		}
		try {
			// With Saxon connections are not limited so we will never get an Exception
			_xqConnection = poolContext.getGlobalContext().getXMLProcessorFactory().getConnection();
			_xqSequence = _xqConnection != null ? _xqConnection.createSequence(Collections.emptyIterator()) : null;
		} catch (XQException e) {
			throw new RuntimeException(e);
		}
	}

	public Deque<Action> getExecutionStack() {
		return _executionStack;
	}

	public Deque<Action> getStackErrorHandler() {
		return _stackErrorHandler;
	}

	public void pushStackPos() {
		_stackPos.push(_executionStack.size());
	}

	public void unwindStack() {
		int stackPos = _stackPos.pop();
		while (_executionStack.size() > stackPos) {
			_executionStack.pop();
		}
	}

	public Deque<Integer> getStackPos() {
		return _stackPos;
	}

	public TimeGauge getTimeGauge() {
		return _timeGauge;
	}

	public PoolContext getPoolContext() {
		return _poolContext;
	}

	public GlobalContext getGlobalContext() {
		return _poolContext.getGlobalContext();
	}

	public SAXParser getSAXParser() throws ParserConfigurationException, SAXException {
		if (_saxParser == null) {
			_saxParser = XMLProcessorFactory.getSAXParserFactory().newSAXParser();
		}
		return _saxParser;
	}

	public FastInfosetReader getFastInfosetDeserializer() {
		if (_fastInfosetDeserializer == null) {
			_fastInfosetDeserializer = new com.sun.xml.fastinfoset.sax.SAXDocumentParser();
		}
		return _fastInfosetDeserializer;
	}

	public void transform(Source source, Result result, Properties serializationParameters) throws TransformerException {
		_transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
		_transformer.setOutputProperty(OutputKeys.INDENT, XML_OUTPUT_INDENT);
		if (serializationParameters != null) {
			_transformer.setOutputProperties(serializationParameters);
		}
		transformRaw(source, result);
	}

	public void writeItem(XQItemAccessor xqItem, OutputStream outputStream, Properties serializationParameters) throws Exception {
		if (XMLProcessorFactory.USE_DEFAULT_IDENTITY_TRANSFORMER) {
			transform(new DOMSource(xqItem.getNode()), new StreamResult(outputStream), serializationParameters);
		} else {
			xqItem.writeItem(outputStream, serializationParameters);
		}
	}

	public void writeItem(XQItemAccessor xqItem, Writer writer, Properties serializationParameters) throws Exception {
		if (XMLProcessorFactory.USE_DEFAULT_IDENTITY_TRANSFORMER) {
			transform(new DOMSource(xqItem.getNode()), new StreamResult(writer), serializationParameters);
		} else {
			xqItem.writeItem(writer, serializationParameters);
		}
	}

	public void transformRaw(Source source, Result result) throws TransformerException {
		try {
			_transformer.transform(source, result);
		} finally {
			_transformer.reset();
		}
	}

	public NamespaceBeautifier createNamespaceBeautifier(Source source) throws TransformerException {
		// If performance need to be improved, we could cache the transformer
		return new NamespaceBeautifier(source, XMLProcessorFactory.newTransformer());
	}

	public void writeBeautified(Node node, Writer writer) throws TransformerException {
		transform(new SAXSource(createNamespaceBeautifier(new DOMSource(node)), null), new StreamResult(writer), null);
	}

	public XQDataFactory getXQDataFactory() {
		return _xqConnection;
	}

	public XQSequence getXQEmptySequence() {
		return _xqSequence;
	}

	public XQPreparedExpression getXQPreparedExpression(XQuerySource xquery, String baseURI) throws XQException {
		XQPreparedExpression preparedExpression = _mapXQ.get(xquery);
		if (preparedExpression == null) {
			preparedExpression = xquery.prepareExpression(_xqConnection, baseURI);
			_mapXQ.put(xquery, preparedExpression);
			_poolContext.addCachedXQuery(xquery);
		}
		return preparedExpression;
	}

	public void putResource(String key, Object resource) {
		_resources.put(key, resource);
	}

	@SuppressWarnings("unchecked")
	public <T> T getResource(String key) {
		return (T) _resources.get(key);
	}

	@SuppressWarnings("unchecked")
	public <T> T removeResource(String key) {
		return (T) _resources.remove(key);
	}

	public boolean isTransacted() {
		return _resources.containsKey(ESBConstants.JDBCConnections);
	}

	@Override
	public void close() {
		try {
			for (Map.Entry<XQuerySource, XQPreparedExpression> entry : _mapXQ.entrySet()) {
				_poolContext.removeCachedXQuery(entry.getKey());
				entry.getValue().close();
			}
			if (_xqConnection != null) {
				_xqConnection.close();
			}
		} catch (XQException e) {
			// ignore
		}
		super.close();
	}

}
