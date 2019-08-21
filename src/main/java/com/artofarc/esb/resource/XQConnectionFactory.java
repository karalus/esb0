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

import javax.xml.transform.URIResolver;
import javax.xml.xquery.XQConnection;
import javax.xml.xquery.XQException;
import javax.xml.xquery.XQStaticContext;

import com.artofarc.util.ReflectionUtils;

public abstract class XQConnectionFactory {

	private static final Constructor<? extends XQConnectionFactory> conXQConnectionFactory = ReflectionUtils.findConstructor(
			System.getProperty("esb0.XQConnectionFactory", XQDataSourceFactory.class.getName()), URIResolver.class);

	public static XQConnectionFactory newInstance(URIResolver uriResolver) {
		try {
			return conXQConnectionFactory.newInstance(uriResolver);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	public abstract XQConnection getConnection() throws XQException;

	public abstract XQStaticContext getStaticContext(XQConnection connection, String baseURI) throws XQException;

}
