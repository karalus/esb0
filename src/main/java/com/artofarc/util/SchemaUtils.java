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

import java.util.ArrayList;

import javax.xml.XMLConstants;
import javax.xml.transform.Source;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.xml.sax.SAXException;

import com.artofarc.esb.artifact.SchemaArtifact;
import com.artofarc.esb.artifact.XMLCatalog;

public final class SchemaUtils {

	/**
	 * This is a hack to allow for global caching of grammars. Relies on Xerces.
	 */
	public static Schema createXMLSchema(SchemaArtifact schemaArtifact, Source[] schemas) throws SAXException {
		SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
		try {
			if (schemaArtifact.getGrammars().size() > 0) {
				ArrayList<Object> grammars = new ArrayList<>();
				collectGrammars(schemaArtifact, grammars);
				return createXMLSchema(factory, ReflectionUtils.toArray(grammars));
			}
			factory.setResourceResolver(schemaArtifact);
			Schema schema = factory.newSchema(schemas);
			Object[] grammars = ReflectionUtils.eval(schema, "grammarPool.retrieveInitialGrammarSet($1)", XMLConstants.W3C_XML_SCHEMA_NS_URI);
			boolean fromCache = false;
			for (int i = 0; i < grammars.length; ++i) {
				Object grammar = grammars[i];
				Object grammarDescription = ReflectionUtils.eval(grammar, "grammarDescription");
				String baseURI = ReflectionUtils.eval(grammarDescription, "baseSystemId");
				String systemId = ReflectionUtils.eval(grammarDescription, "literalSystemId");
				String uri = XMLCatalog.alignSystemId(systemId);
				SchemaArtifact base = schemaArtifact;
				if (baseURI != null) {
					if (!baseURI.startsWith(SchemaArtifact.FILE_SCHEMA)) {
						throw new IllegalArgumentException("baseURI must start with " + SchemaArtifact.FILE_SCHEMA);
					}
					base = base.getArtifact(baseURI.substring(SchemaArtifact.FILE_SCHEMA.length()));
				}
				SchemaArtifact artifact = base.getArtifact(uri);
				String namespace = ReflectionUtils.eval(grammarDescription, "namespace");
				Object cachedGrammar = artifact.putGrammarIfAbsent(namespace, grammar);
				if (cachedGrammar != null) {
					grammars[i] = cachedGrammar;
					fromCache = true;
				}
			}
			if (fromCache) {
				return createXMLSchema(factory, grammars);
			}
			return schema;
		} catch (ReflectiveOperationException e) {
			throw new SAXException(e);
		}
	}

	private static Schema createXMLSchema(SchemaFactory factory, Object[] grammars) throws ReflectiveOperationException {
		Object schema;
		if (grammars.length == 1) {
			schema = ReflectionUtils.newInstancePackage(factory, "SimpleXMLSchema", grammars[0]);
		} else {
			Object grammarPool = ReflectionUtils.newInstanceInnerStatic(factory, "XMLGrammarPoolImplExtension");
			ReflectionUtils.eval(grammarPool, "cacheGrammars($1,$2)", XMLConstants.W3C_XML_SCHEMA_NS_URI, grammars);
			schema = ReflectionUtils.newInstancePackage(factory, "XMLSchema", ReflectionUtils.newInstancePackage(factory, "ReadOnlyGrammarPool", grammarPool));
		}
		ReflectionUtils.eval(factory, "propagateFeatures($1)", schema);
		try {
			ReflectionUtils.eval(factory, "propagateProperties($1)", schema);
		} catch (NoSuchMethodException e) {
			// Newer Xerces does not have this method
		}
		return (Schema) schema;
	}

	private static void collectGrammars(SchemaArtifact schemaArtifact, ArrayList<Object> grammars) {
		for (Object grammar : schemaArtifact.getGrammars().values()) {
			grammars.add(grammar);
		}
		for (String referenced : schemaArtifact.getReferenced()) {
			SchemaArtifact artifact = schemaArtifact.getArtifact(referenced);
			collectGrammars(artifact, grammars);
		}
	}

}
