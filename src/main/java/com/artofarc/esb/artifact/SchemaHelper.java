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

import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import javax.xml.XMLConstants;
import javax.xml.transform.Source;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.xml.sax.SAXException;

import com.artofarc.util.ReflectionUtils;

/**
 * This is a hack to allow for global caching of grammars used by Schema instances. Relies on Xerces (either original or JDK).
 * @see Schema
 * @see <a href="https://www.ibm.com/developerworks/xml/library/x-perfap3/index.html">Xerces2 grammar caching API</a>
 */
public final class SchemaHelper implements InvocationHandler {

	private final static String XERCES_XMLGRAMMAR_POOL_PROPERTY = "http://apache.org/xml/properties/internal/grammar-pool";
	private final static long timeout = Long.parseLong(System.getProperty("esb0.cacheXSGrammars.timeout", "100"));

	private final static Constructor<? extends SchemaFactory> conSchemaFactory; 
	private final static Field fXMLSchemaLoader;

	private static boolean isXercesSchemaFactory(Object object) {
		String className = object.getClass().getName();
		return className.contains("org.apache.xerces") && className.endsWith("jaxp.validation.XMLSchemaFactory");
	}

	static {
		SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
		try {
			if (!isXercesSchemaFactory(factory)) {
				// Maybe we have got a simple wrapper so search inside
				for (Field field : factory.getClass().getDeclaredFields()) {
					field.setAccessible(true);
					Object object = field.get(factory);
					if (isXercesSchemaFactory(object)) {
						factory = (SchemaFactory) object;
						break;
					}
				}
			}
			conSchemaFactory = factory.getClass().getConstructor();
			fXMLSchemaLoader = factory.getClass().getDeclaredField("fXMLSchemaLoader");
			fXMLSchemaLoader.setAccessible(true);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException("SchemaFactory not Xerces", e);
		}
	}

	private final SchemaArtifact _schemaArtifact;
	private final Object _grammarPool;

	private SchemaHelper(SchemaArtifact schemaArtifact, Object grammarPool) {
		_schemaArtifact = schemaArtifact;
		_grammarPool = grammarPool;
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Exception {
		switch (method.getName()) {
		case "retrieveInitialGrammarSet":
			if (!XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(args[0])) {
				throw new IllegalArgumentException("Only XML Schema is supported");
			}
			return method.invoke(_grammarPool, args);
		case "retrieveGrammar":
			Object xmlGrammarDescription = args[0];
			String namespace = ReflectionUtils.eval(xmlGrammarDescription, "namespace");
			String systemId = XMLCatalog.alignSystemId(ReflectionUtils.<String> eval(xmlGrammarDescription, "literalSystemId"));
			String baseSystemId = ReflectionUtils.eval(xmlGrammarDescription, "baseSystemId");
			try {
				SchemaArtifact artifact;
				if (baseSystemId != null) {
					artifact = _schemaArtifact.resolveArtifact(systemId, baseSystemId);
				} else {
					artifact = _schemaArtifact.loadArtifact(systemId);
				}
				if (artifact != _schemaArtifact) {
					// don't recurse when it is already started. Will result in stack overflow caused by circular dependencies. 
					if (artifact._namespace.compareAndSet(null, namespace)) {
						artifact._schema = createXMLSchema(artifact, artifact.getSourcesForSchema());
					} else {
						Object grammar = artifact.getGrammars().get(namespace);
						if (grammar != null) return grammar;
						synchronized (artifact) {
							artifact.wait(timeout);
						}
						if (artifact.getGrammars().get(namespace) == null) {
							Artifact.logger.warn(artifact.getURI() + " not initialized, yet. BaseURI is " + baseSystemId);
						}
					}
					return artifact.getGrammars().get(namespace);
				} else {
					if (_schemaArtifact instanceof XSDArtifact) {
						_schemaArtifact._namespace.set(namespace);
					}
					return null;
				}
			} catch (FileNotFoundException | SAXException e) {
				throw new RuntimeException(e);
			}
		case "cacheGrammars":
			Object[] grammars = (Object[]) args[1];
			synchronized (_schemaArtifact) {
				for (Object grammar : grammars) {
					_schemaArtifact.getGrammars().put(ReflectionUtils.<String> eval(grammar, "grammarDescription.namespace"), grammar);
				}
				_schemaArtifact.notifyAll();
			}
			// write back
			return method.invoke(_grammarPool, args);
		default:
			throw new UnsupportedOperationException("Method not implemented: " + method.getName());
		}
	}

	public static Schema createXMLSchema(SchemaArtifact schemaArtifact, Source[] schemas) throws ReflectiveOperationException, SAXException {
		SchemaFactory factory = conSchemaFactory.newInstance();
		factory.setResourceResolver(schemaArtifact.getResolver());
		Object xmlSchemaLoader = fXMLSchemaLoader.get(factory);
		Object grammarPool = ReflectionUtils.eval(xmlSchemaLoader, "getProperty($1)", XERCES_XMLGRAMMAR_POOL_PROPERTY);
		Class<?> cls = grammarPool.getClass();
		Object proxyInstance = Proxy.newProxyInstance(cls.getClassLoader(), cls.getInterfaces(), new SchemaHelper(schemaArtifact, grammarPool));
		ReflectionUtils.eval(xmlSchemaLoader, "setProperty($1,$2)", XERCES_XMLGRAMMAR_POOL_PROPERTY, proxyInstance);
		// This uses now our XMLGrammarPool for lookup
		Schema schema = factory.newSchema(schemas);
		try {
			// Remove references to SchemaArtifact thus prevent memory leak on provisioning
			ReflectionUtils.eval(schema, "setProperty($1,$2)", XERCES_XMLGRAMMAR_POOL_PROPERTY, grammarPool);
		} catch (NoSuchMethodException e) {
			// Recent Xerces does not have this anymore
		}
		return schema;
	}

	public static void printGrammars(Schema schema, PrintStream printStream) throws ReflectiveOperationException {
		Object[] grammars = ReflectionUtils.eval(schema, "grammarPool.retrieveInitialGrammarSet($1)", XMLConstants.W3C_XML_SCHEMA_NS_URI);
		for (Object grammar : grammars) {
			Object grammarDescription = ReflectionUtils.eval(grammar, "grammarDescription");
			printStream.println(ReflectionUtils.eval(grammarDescription, "namespace") + ": " + ReflectionUtils.eval(grammarDescription, "baseSystemId") + "->"
					+ ReflectionUtils.eval(grammarDescription, "literalSystemId") + ": " + System.identityHashCode(grammar));
		}
	}

}
