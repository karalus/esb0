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

import java.nio.charset.StandardCharsets;

import javax.xml.namespace.QName;
import javax.xml.xquery.XQConnection;
import javax.xml.xquery.XQException;
import javax.xml.xquery.XQPreparedExpression;
import javax.xml.xquery.XQSequenceType;
import javax.xml.xquery.XQStaticContext;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.resource.XQDataSourceFactory;

public class XQueryArtifact extends XMLArtifact {

	public XQueryArtifact(Directory parent, String name) {
		super(parent, name);
	}

	@Override
	protected XQueryArtifact clone(Directory parent) {
		return initClone(new XQueryArtifact(parent, getName()));
	}

	public String getXQuery() {
		// TODO: UTF-8 is just an assumption
		return new String(getContent(), StandardCharsets.UTF_8);
	}

	@Override
	public void validateInternal(GlobalContext globalContext) throws XQException {
		// Needs an individual XQDataSourceFactory to track the use of modules
		XQConnection connection = new XQDataSourceFactory(getURIResolver()).createXQDataSource().getConnection();
		try {
			XQStaticContext staticContext = XQDataSourceFactory.getStaticContext(connection, getParent().getURI());
			staticContext.declareNamespace(XQDataSourceFactory.XPATH_EXTENSION_NS_PREFIX, XQDataSourceFactory.XPATH_EXTENSION_NS_URI);
			logger.info("Parsing XQuery: " + getURI());
			XQPreparedExpression preparedExpression = connection.prepareExpression(getContentAsByteArrayInputStream(), staticContext);
			for (QName qName : preparedExpression.getAllExternalVariables()) {
				logger.info("External variable: " + qName + ", Type: " + preparedExpression.getStaticVariableType(qName));
			}
			logger.info("is result occurrence exactly one: " + (preparedExpression.getStaticResultType().getItemOccurrence() == XQSequenceType.OCC_EXACTLY_ONE));
			preparedExpression.close();
			// set modules to validated 
			for (String referenced : getReferenced()) {
				getArtifact(referenced).setValidated(true);
			}
		} finally {
			connection.close();
		}
	}

}
