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

import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;
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
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.type.BuiltInAtomicType;
import net.sf.saxon.value.Int64Value;
import net.sf.saxon.value.SequenceType;
import net.sf.saxon.value.StringValue;

import com.artofarc.esb.artifact.Artifact;
import com.saxonica.xqj.SaxonXQDataSource;

public abstract class XQDataSourceFactory implements URIResolver, ModuleURIResolver {
	
	public static final String XPATH_EXTENSION_NS_URI = "http://artofarc.com/xpath-extension";
	public static final String XPATH_EXTENSION_NS_PREFIX = "fn-artofarc";

	private final static UUID functionUUID = new UUID();
	private final static CurrentTimeMillis functionCurrentTimeMillis = new CurrentTimeMillis();
	
	public XQDataSource createXQDataSource() {
		SaxonXQDataSource dataSource = new SaxonXQDataSource();
		Configuration configuration = dataSource.getConfiguration();
		configuration.registerExtensionFunction(functionUUID);
		configuration.registerExtensionFunction(functionCurrentTimeMillis);
		configuration.setModuleURIResolver(this);
		configuration.setURIResolver(this);
		return dataSource;
	}
	
	public static XQStaticContext getStaticContext(XQConnection connection, String baseURI) throws XQException {
		XQStaticContext staticContext = connection.getStaticContext();
		// In Saxon baseURI must not be an empty string
		staticContext.setBaseURI(baseURI.isEmpty() ? "/." : baseURI);
		return staticContext;
	}

	@Override
	public StreamSource resolve(String href, String base) throws TransformerException {
		String path = base + href;
		Artifact artifact = resolveArtifact(path);
		if (artifact == null) {
			throw new TransformerException("document not found: " + path);
		}
		return new StreamSource(artifact.getContentAsByteArrayInputStream());
	}

	@Override
	public StreamSource[] resolve(String moduleURI, String baseURI, String[] locations) throws XPathException {
		StreamSource[] result = new StreamSource[locations.length];
		for (int i = 0; i < locations.length; ++i) {
			String path = baseURI + locations[i];
			Artifact artifact = resolveArtifact(path);
			if (artifact == null) {
				throw new XPathException("module not found: " + path);
			}
			result[i] = new StreamSource(artifact.getContentAsByteArrayInputStream());
		}
		return result;
	}
	
	public abstract Artifact resolveArtifact(String path);
	
	private static class UUID extends ExtensionFunctionDefinition {

		@Override
		public StructuredQName getFunctionQName() {
			return new StructuredQName(XPATH_EXTENSION_NS_PREFIX, XPATH_EXTENSION_NS_URI, "uuid");
		}

		@Override
		public SequenceType[] getArgumentTypes() {
			return new SequenceType[] {};
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
			return new SequenceType[] {};
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

}
