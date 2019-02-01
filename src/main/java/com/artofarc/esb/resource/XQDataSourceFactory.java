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
package com.artofarc.esb.resource;

import java.util.HashMap;

import javax.xml.transform.stream.StreamSource;
import javax.xml.xquery.XQConnection;
import javax.xml.xquery.XQDataSource;
import javax.xml.xquery.XQException;
import javax.xml.xquery.XQStaticContext;

import net.sf.saxon.Configuration;
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
import net.sf.saxon.value.SequenceExtent;
import net.sf.saxon.value.SequenceType;
import net.sf.saxon.value.StringValue;

import com.artofarc.esb.artifact.Artifact;
import com.artofarc.esb.artifact.ArtifactURIResolver;
import com.saxonica.xqj.SaxonXQDataSource;

public final class XQDataSourceFactory implements ModuleURIResolver {

	public static final String XPATH_EXTENSION_NS_URI = "http://artofarc.com/xpath-extension";
	public static final String XPATH_EXTENSION_NS_PREFIX = "fn-artofarc";

	private final static UUID functionUUID = new UUID();
	private final static CurrentTimeMillis functionCurrentTimeMillis = new CurrentTimeMillis();
	
	// Is instance variable because it maintains state
	private final Evaluate functionEvaluate = new Evaluate();
	private final ArtifactURIResolver _artifactURIResolver;
	
	public XQDataSourceFactory(ArtifactURIResolver artifactURIResolver) {
		_artifactURIResolver = artifactURIResolver;
	}

	public XQDataSource createXQDataSource() {
		SaxonXQDataSource dataSource = new SaxonXQDataSource();
		Configuration configuration = dataSource.getConfiguration();
		configuration.registerExtensionFunction(functionUUID);
		configuration.registerExtensionFunction(functionCurrentTimeMillis);
		configuration.registerExtensionFunction(functionEvaluate);
		configuration.setModuleURIResolver(this);
		configuration.setURIResolver(_artifactURIResolver);
		return dataSource;
	}

	public static XQStaticContext getStaticContext(XQConnection connection, String baseURI) throws XQException {
		XQStaticContext staticContext = connection.getStaticContext();
		// In Saxon baseURI must not be an empty string
		staticContext.setBaseURI(baseURI.isEmpty() ? "/." : baseURI);
		return staticContext;
	}

	@Override
	public StreamSource[] resolve(String moduleURI, String baseURI, String[] locations) throws XPathException {
		StreamSource[] result = new StreamSource[locations.length];
		for (int i = 0; i < locations.length; ++i) {
			Artifact artifact = _artifactURIResolver.resolveArtifact(locations[i]);
			if (artifact == null) {
				artifact = _artifactURIResolver.resolveArtifact(baseURI + locations[i]);
				if (artifact == null) {
					throw new XPathException(locations[i] + " in " + baseURI);
				}
			}
			result[i] = new StreamSource(artifact.getContentAsByteArrayInputStream());
			result[i].setSystemId(artifact.getURI());
		}
		return result;
	}

	private static class UUID extends ExtensionFunctionDefinition {

		@Override
		public StructuredQName getFunctionQName() {
			return new StructuredQName(XPATH_EXTENSION_NS_PREFIX, XPATH_EXTENSION_NS_URI, "uuid");
		}

		@Override
		public SequenceType[] getArgumentTypes() {
			return new SequenceType[0];
		}

		@Override
		public SequenceType getResultType(SequenceType[] suppliedArgumentTypes) {
			return SequenceType.SINGLE_STRING;
		}

		@Override
		public ExtensionFunctionCall makeCallExpression() {
			return new ExtensionFunctionCall() {

				@Override
				public Sequence call(XPathContext context, Sequence[] arguments) {
					return StringValue.makeStringValue(java.util.UUID.randomUUID().toString());
				}

			};
		}
	}

	private static class CurrentTimeMillis extends ExtensionFunctionDefinition {

		@Override
		public StructuredQName getFunctionQName() {
			return new StructuredQName(XPATH_EXTENSION_NS_PREFIX, XPATH_EXTENSION_NS_URI, "currentTimeMillis");
		}

		@Override
		public SequenceType[] getArgumentTypes() {
			return new SequenceType[0];
		}

		@Override
		public SequenceType getResultType(SequenceType[] suppliedArgumentTypes) {
			return BuiltInAtomicType.LONG.one();
		}

		@Override
		public ExtensionFunctionCall makeCallExpression() {
			return new ExtensionFunctionCall() {

				@Override
				public Sequence call(XPathContext context, Sequence[] arguments) {
					return Int64Value.makeDerived(System.currentTimeMillis(), BuiltInAtomicType.LONG);
				}

			};
		}
	}

	private static class Evaluate extends ExtensionFunctionDefinition {

		private XPathEvaluator _xPathEvaluator;
		private final HashMap<String, XPathExpression> _cache = new HashMap<>();

		private synchronized XPathExpression getXPathExpression(XPathContext context, String expression) throws XPathException {
			XPathExpression xPathExpression = _cache.get(expression);
			if (xPathExpression == null) {
				if (_xPathEvaluator == null) {
					_xPathEvaluator = new XPathEvaluator(context.getConfiguration());
				}
				xPathExpression = _xPathEvaluator.createExpression(expression);
				_cache.put(expression, xPathExpression);
			}
			return xPathExpression;
		}

		@Override
		public StructuredQName getFunctionQName() {
			return new StructuredQName(XPATH_EXTENSION_NS_PREFIX, XPATH_EXTENSION_NS_URI, "evaluate");
		}

		@Override
		public SequenceType[] getArgumentTypes() {
			return new SequenceType[] { BuiltInAtomicType.STRING.one() };
		}

		@Override
		public SequenceType getResultType(SequenceType[] suppliedArgumentTypes) {
			return SequenceType.ANY_SEQUENCE;
		}

		@Override
		public ExtensionFunctionCall makeCallExpression() {
			return new ExtensionFunctionCall() {

				@Override
				public Sequence call(XPathContext context, Sequence[] arguments) throws XPathException {
					StringValue xpath = (StringValue) arguments[0];
					XPathExpression xPathExpression = getXPathExpression(context, xpath.getStringValue());
					XPathDynamicContext dynamicContext = xPathExpression.createDynamicContext(context.getController(), context.getContextItem());
					return SequenceExtent.makeSequenceExtent(xPathExpression.iterate(dynamicContext));
				}

			};
		}
	}

}
