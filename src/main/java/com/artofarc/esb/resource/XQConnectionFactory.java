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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

import javax.xml.transform.ErrorListener;
import javax.xml.transform.URIResolver;
import javax.xml.xquery.XQConnection;
import javax.xml.xquery.XQException;

import com.artofarc.util.ReflectionUtils;

public class XQConnectionFactory {

	public static final String XPATH_EXTENSION_NS_URI = "http://artofarc.com/xpath-extension";
	public static final String XPATH_EXTENSION_NS_PREFIX = "fn-artofarc";

	private static final MethodHandle conXQConnectionFactory;

	static {
		try {
			conXQConnectionFactory = MethodHandles.lookup().unreflectConstructor(ReflectionUtils.findConstructor(
				System.getProperty("esb0.XQConnectionFactory", "com.artofarc.util.saxon.SaxonXMLProcessorFactory"), URIResolver.class));
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	public static XQConnectionFactory newInstance(URIResolver uriResolver) {
		try {
			return (XQConnectionFactory) conXQConnectionFactory.invoke(uriResolver);
		} catch (Throwable e) {
			throw ReflectionUtils.convert(e, RuntimeException.class);
		}
	}

	protected final URIResolver _uriResolver;

	protected XQConnectionFactory(URIResolver uriResolver) {
		_uriResolver = uriResolver;
	}

	public void setErrorListener(ErrorListener listener) {
	}

	public XQConnection getConnection() throws XQException {
		return null;
	}

}
