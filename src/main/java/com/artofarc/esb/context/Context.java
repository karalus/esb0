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
package com.artofarc.esb.context;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map.Entry;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.xquery.XQConnection;
import javax.xml.xquery.XQDataFactory;
import javax.xml.xquery.XQException;
import javax.xml.xquery.XQPreparedExpression;

import org.xml.sax.SAXException;

import com.artofarc.esb.action.Action;
import com.artofarc.util.FastInfosetDeserializer;
import com.artofarc.util.SAXTransformerFactoryHelper;
import com.artofarc.util.TimeGauge;

public final class Context extends AbstractContext {

	private static final DocumentBuilderFactory DOCUMENT_BUILDER_FACTORY = DocumentBuilderFactory.newInstance();
	private static final SAXParserFactory SAX_PARSER_FACTORY = SAXParserFactory.newInstance();

	static {
		DOCUMENT_BUILDER_FACTORY.setNamespaceAware(true);
		SAX_PARSER_FACTORY.setNamespaceAware(true);
		try {
			SAX_PARSER_FACTORY.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
		} catch (ParserConfigurationException | SAXException e) {
			throw new RuntimeException(e);
		}
	}

	private final PoolContext _poolContext;
	private final Transformer _transformer;
	private final XQConnection _xqConnection;
	private final HashMap<XQuerySource, XQPreparedExpression> _mapXQ = new HashMap<>();
	private final TimeGauge _timeGauge = new TimeGauge(logger);
	private final Deque<Action> _executionStack = new ArrayDeque<>();
	private final ArrayDeque<Action> _stackErrorHandler = new ArrayDeque<>();
	private final ArrayDeque<Integer> _stackPos = new ArrayDeque<>();

	private DocumentBuilder _documentBuilder;
	private SAXParser _saxParser;
	private FastInfosetDeserializer _fastInfosetDeserializer;

	public Context(PoolContext poolContext) {
		_poolContext = poolContext;
		try {
			_transformer = SAXTransformerFactoryHelper.newTransformer();
			_transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
			// With Saxon connections are not limited so we will never get an Exception
			_xqConnection = poolContext.getGlobalContext().getXQConnectionFactory().getConnection();
		} catch (TransformerConfigurationException | XQException e) {
			throw new RuntimeException("Cannot initialize context", e);
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

	public DocumentBuilder getDocumentBuilder() throws ParserConfigurationException {
		if (_documentBuilder == null) {
			_documentBuilder = DOCUMENT_BUILDER_FACTORY.newDocumentBuilder();
		}
		return _documentBuilder;
	}

	public SAXParser getSAXParser() throws ParserConfigurationException, SAXException {
		if (_saxParser == null) {
			_saxParser = SAX_PARSER_FACTORY.newSAXParser();
		}
		return _saxParser;
	}

	public FastInfosetDeserializer getFastInfosetDeserializer() {
		if (_fastInfosetDeserializer == null) {
			_fastInfosetDeserializer = new FastInfosetDeserializer();
		}
		return _fastInfosetDeserializer;
	}

	public Transformer getIdenticalTransformer() {
		return _transformer;
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
