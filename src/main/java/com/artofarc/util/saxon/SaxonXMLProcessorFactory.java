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
package com.artofarc.util.saxon;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;
import javax.xml.transform.stream.StreamSource;
import javax.xml.xquery.XQConnection;
import javax.xml.xquery.XQConstants;
import javax.xml.xquery.XQException;
import javax.xml.xquery.XQStaticContext;

import com.artofarc.util.XMLProcessorFactory;
import com.saxonica.xqj.SaxonXQDataSource;

import net.sf.saxon.Configuration;
import net.sf.saxon.TransformerFactoryImpl;
import net.sf.saxon.expr.XPathContext;
import net.sf.saxon.lib.ExtensionFunctionCall;
import net.sf.saxon.lib.ExtensionFunctionDefinition;
import net.sf.saxon.lib.ModuleURIResolver;
import net.sf.saxon.om.Sequence;
import net.sf.saxon.om.StructuredQName;
import net.sf.saxon.sxpath.XPathDynamicContext;
import net.sf.saxon.sxpath.XPathEvaluator;
import net.sf.saxon.sxpath.XPathExpression;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.type.BuiltInAtomicType;
import net.sf.saxon.value.Int64Value;
import net.sf.saxon.value.SequenceType;
import net.sf.saxon.value.StringValue;

public final class SaxonXMLProcessorFactory extends XMLProcessorFactory implements ModuleURIResolver {

	private final static UUID functionUUID = new UUID();
	private final static CurrentTimeMillis functionCurrentTimeMillis = new CurrentTimeMillis();
	private final static Random functionRandom = new Random();

	private final URIResolver _uriResolver;
	private final SaxonXQDataSource _dataSource;

	public SaxonXMLProcessorFactory(URIResolver uriResolver) throws Throwable {
		super(uriResolver);
		_uriResolver = uriResolver;
		Configuration configuration = ((TransformerFactoryImpl) _saxTransformerFactory).getConfiguration();
		configuration.registerExtensionFunction(functionUUID);
		configuration.registerExtensionFunction(functionCurrentTimeMillis);
		configuration.registerExtensionFunction(functionRandom);
		configuration.registerExtensionFunction(new Evaluate(configuration));
		configuration.setModuleURIResolver(this);
		// Avoid concurrency bug in Saxon by eager initializing
		configuration.getBuiltInExtensionLibraryList();
		_dataSource = new SaxonXQDataSource(configuration);
	}

	@Override
	public XQConnection getConnection() throws XQException {
		XQConnection connection = _dataSource.getConnection();
		XQStaticContext staticContext = connection.getStaticContext();
		staticContext.setBindingMode(XQConstants.BINDING_MODE_DEFERRED);
		staticContext.declareNamespace(XPATH_EXTENSION_NS_PREFIX, XPATH_EXTENSION_NS_URI);
		connection.setStaticContext(staticContext);
		return connection;
	}

	@Override
	public StreamSource[] resolve(String moduleURI, String baseURI, String[] locations) throws XPathException {
		StreamSource[] result = new StreamSource[locations.length];
		for (int i = 0; i < locations.length; ++i) {
			try {
				result[i] = (StreamSource) _uriResolver.resolve(locations[i], baseURI);
			} catch (TransformerException e) {
				// In this case TransformerException is only a wrapper
				throw new XPathException(e.getCause());
			}
		}
		return result;
	}

	private static class UUID extends ExtensionFunctionDefinition {

		private static final StructuredQName FUNCTION_QNAME = new StructuredQName(XPATH_EXTENSION_NS_PREFIX, XPATH_EXTENSION_NS_URI, "uuid");
		private static final SequenceType[] ARGUMENT_TYPES = new SequenceType[0];
		private static final ExtensionFunctionCall EXTENSION_FUNCTION_CALL = new ExtensionFunctionCall() {

			@Override
			public Sequence<?> call(XPathContext context, @SuppressWarnings("rawtypes") Sequence[] arguments) {
				return StringValue.makeStringValue(java.util.UUID.randomUUID().toString());
			}
		};

		@Override
		public StructuredQName getFunctionQName() {
			return FUNCTION_QNAME;
		}

		@Override
		public SequenceType[] getArgumentTypes() {
			return ARGUMENT_TYPES;
		}

		@Override
		public SequenceType getResultType(SequenceType[] suppliedArgumentTypes) {
			return SequenceType.SINGLE_STRING;
		}

		@Override
		public ExtensionFunctionCall makeCallExpression() {
			return EXTENSION_FUNCTION_CALL;
		}
	}

	private static class CurrentTimeMillis extends ExtensionFunctionDefinition {

		private static final StructuredQName FUNCTION_QNAME = new StructuredQName(XPATH_EXTENSION_NS_PREFIX, XPATH_EXTENSION_NS_URI, "currentTimeMillis");
		private static final SequenceType[] ARGUMENT_TYPES = new SequenceType[0];
		private static final ExtensionFunctionCall EXTENSION_FUNCTION_CALL = new ExtensionFunctionCall() {

			@Override
			public Sequence<?> call(XPathContext context, @SuppressWarnings("rawtypes") Sequence[] arguments) {
				return Int64Value.makeDerived(System.currentTimeMillis(), BuiltInAtomicType.LONG);
			}
		};

		@Override
		public StructuredQName getFunctionQName() {
			return FUNCTION_QNAME;
		}

		@Override
		public SequenceType[] getArgumentTypes() {
			return ARGUMENT_TYPES;
		}

		@Override
		public SequenceType getResultType(SequenceType[] suppliedArgumentTypes) {
			return BuiltInAtomicType.LONG.one();
		}

		@Override
		public ExtensionFunctionCall makeCallExpression() {
			return EXTENSION_FUNCTION_CALL;
		}
	}

	private static class Random extends ExtensionFunctionDefinition {

		private static final StructuredQName FUNCTION_QNAME = new StructuredQName(XPATH_EXTENSION_NS_PREFIX, XPATH_EXTENSION_NS_URI, "random-next-long");
		private static final SequenceType[] ARGUMENT_TYPES = new SequenceType[] { BuiltInAtomicType.INTEGER.one(), BuiltInAtomicType.INTEGER.one() };
		private static final ExtensionFunctionCall EXTENSION_FUNCTION_CALL = new ExtensionFunctionCall() {

			@Override
			public Sequence<?> call(XPathContext context, @SuppressWarnings("rawtypes") Sequence[] arguments) throws XPathException {
				Int64Value origin = (Int64Value) arguments[0].head();
				Int64Value bound = (Int64Value) arguments[1].head();
				return Int64Value.makeDerived(ThreadLocalRandom.current().nextLong(origin.longValue(), bound.longValue()), BuiltInAtomicType.LONG);
			}
		};

		@Override
		public StructuredQName getFunctionQName() {
			return FUNCTION_QNAME;
		}

		@Override
		public SequenceType[] getArgumentTypes() {
			return ARGUMENT_TYPES;
		}

		@Override
		public SequenceType getResultType(SequenceType[] suppliedArgumentTypes) {
			return BuiltInAtomicType.LONG.one();
		}

		@Override
		public ExtensionFunctionCall makeCallExpression() {
			return EXTENSION_FUNCTION_CALL;
		}
	}

	private static class Evaluate extends ExtensionFunctionDefinition {

		private static final StructuredQName FUNCTION_QNAME = new StructuredQName(XPATH_EXTENSION_NS_PREFIX, XPATH_EXTENSION_NS_URI, "evaluate");
		private static final SequenceType[] ARGUMENT_TYPES = new SequenceType[] { BuiltInAtomicType.STRING.one() };

		private final ExtensionFunctionCall _extensionFunctionCall = new ExtensionFunctionCall() {

			@Override
			public Sequence<?> call(XPathContext context, @SuppressWarnings("rawtypes") Sequence[] arguments) throws XPathException {
				StringValue xpath = (StringValue) arguments[0].head();
				XPathExpression xPathExpression = getXPathExpression(xpath.getStringValue());
				XPathDynamicContext dynamicContext = xPathExpression.createDynamicContext(context.getController(), context.getContextItem());
				return xPathExpression.iterate(dynamicContext).materialize();
			}
		};
		private final ConcurrentHashMap<String, XPathExpression> _cache = new ConcurrentHashMap<>();
		private final XPathEvaluator _xPathEvaluator;

		Evaluate(Configuration configuration) {
			_xPathEvaluator = new XPathEvaluator(configuration);
		}

		private XPathExpression getXPathExpression(String expression) throws XPathException {
			XPathExpression xPathExpression = _cache.get(expression);
			if (xPathExpression == null) {
				xPathExpression = _xPathEvaluator.createExpression(expression);
				_cache.putIfAbsent(expression, xPathExpression);
			}
			return xPathExpression;
		}

		@Override
		public StructuredQName getFunctionQName() {
			return FUNCTION_QNAME;
		}

		@Override
		public SequenceType[] getArgumentTypes() {
			return ARGUMENT_TYPES;
		}

		@Override
		public SequenceType getResultType(SequenceType[] suppliedArgumentTypes) {
			return SequenceType.ANY_SEQUENCE;
		}

		@Override
		public ExtensionFunctionCall makeCallExpression() {
			return _extensionFunctionCall;
		}
	}

}
