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

import java.lang.reflect.Constructor;

import javax.xml.XMLConstants;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.sax.SAXTransformerFactory;

public class SAXTransformerFactoryFactory {

	private static final SAXTransformerFactory SAX_TRANSFORMER_FACTORY = (SAXTransformerFactory) SAXTransformerFactory.newInstance();
	private static final Constructor<? extends SAXTransformerFactory> conSAXTransformerFactory;

	static {
		try {
			SAX_TRANSFORMER_FACTORY.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			conSAXTransformerFactory = SAX_TRANSFORMER_FACTORY.getClass().getConstructor();
		} catch (TransformerConfigurationException | NoSuchMethodException e) {
			throw new RuntimeException(e);
		}
	}

	public static SAXTransformerFactory getSAXTransformerFactory() {
		return SAX_TRANSFORMER_FACTORY;
	}

	public static SAXTransformerFactory createSAXTransformerFactory() {
		try {
			SAXTransformerFactory saxTransformerFactory = conSAXTransformerFactory.newInstance();
			saxTransformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			return saxTransformerFactory;
		} catch (TransformerConfigurationException | ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

}
