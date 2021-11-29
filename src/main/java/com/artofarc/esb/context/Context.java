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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Properties;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.xquery.XQConnection;
import javax.xml.xquery.XQDataFactory;
import javax.xml.xquery.XQException;
import javax.xml.xquery.XQPreparedExpression;

import org.jvnet.fastinfoset.sax.FastInfosetReader;
import org.xml.sax.SAXException;

import com.artofarc.esb.action.Action;
import com.artofarc.util.JAXPFactoryHelper;
import com.artofarc.util.NamespaceBeautifier;
import com.artofarc.util.TimeGauge;
import com.artofarc.util.XQuerySource;

public final class Context extends AbstractContext {

	public static final String XML_OUTPUT_INDENT = System.getProperty("esb0.xmlOutputIndent", "yes");
	public static final Boolean SCHEMA_AWARE_FI_SERIALIZER_IGNORE_WHITESPACE = Boolean.valueOf(System.getProperty("esb0.schemaAwareFastInfosetSerializer.ignoreWhitespace"));

	private final PoolContext _poolContext;
	private final Transformer _transformer;
	private final XQConnection _xqConnection;
	private final HashMap<XQuerySource, XQPreparedExpression> _mapXQ = new HashMap<>();
	private final TimeGauge _timeGauge = new TimeGauge(logger);
	private final Deque<Action> _executionStack = new ArrayDeque<>();
	private final ArrayDeque<Action> _stackErrorHandler = new ArrayDeque<>();
	private final ArrayDeque<Integer> _stackPos = new ArrayDeque<>();

	private SAXParser _saxParser;
	private FastInfosetReader _fastInfosetDeserializer;

	public Context(PoolContext poolContext) {
		_poolContext = poolContext;
		try {
			_transformer = JAXPFactoryHelper.newTransformer();
		} catch (TransformerConfigurationException e) {
			throw new RuntimeException(e);
		}
		try {
			// With Saxon connections are not limited so we will never get an Exception
			_xqConnection = poolContext.getGlobalContext().getXQConnectionFactory().getConnection();
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
			_saxParser = JAXPFactoryHelper.getSAXParserFactory().newSAXParser();
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

	public void transformRaw(Source source, Result result) throws TransformerException {
		try {
			_transformer.transform(source, result);
		} finally {
			_transformer.reset();
		}
	}

	public NamespaceBeautifier createNamespaceBeautifier(Source source) throws TransformerException {
		// If performance need to be improved, we could cache the transformer
		return new NamespaceBeautifier(source, JAXPFactoryHelper.newTransformer());
	}

	public XQDataFactory getXQDataFactory() {
		return _xqConnection;
	}

	public XQPreparedExpression getXQPreparedExpression(XQuerySource xquery, String baseURI) throws XQException {
		XQPreparedExpression preparedExpression = _mapXQ.get(xquery);
		if (preparedExpression == null) {
			preparedExpression = xquery.prepareExpression(_xqConnection, baseURI);
			_mapXQ.put(xquery, preparedExpression);
			_poolContext.getWorkerPool().addCachedXQuery(xquery);
		}
		return preparedExpression;
	}

	@Override
	public void close() {
		try {
			for (Entry<XQuerySource, XQPreparedExpression> entry : _mapXQ.entrySet()) {
				_poolContext.getWorkerPool().removeCachedXQuery(entry.getKey());
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
