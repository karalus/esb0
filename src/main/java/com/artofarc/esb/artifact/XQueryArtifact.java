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

import javax.xml.namespace.QName;
import javax.xml.xquery.XQConnection;
import javax.xml.xquery.XQException;
import javax.xml.xquery.XQPreparedExpression;
import javax.xml.xquery.XQSequenceType;
import javax.xml.xquery.XQStaticContext;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.XQuerySource;
import com.artofarc.esb.resource.XQDataSourceFactory;

public class XQueryArtifact extends XMLArtifact {

	public XQueryArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	@Override
	protected XQueryArtifact clone(FileSystem fileSystem, Directory parent) {
		return initClone(new XQueryArtifact(fileSystem, parent, getName()));
	}

	static void validateXQuerySource(Artifact owner, XQuerySource xQuerySource) throws XQException {
		// Needs an individual XQDataSourceFactory to track the use of modules
		XQConnection connection = new XQDataSourceFactory(owner.getURIResolver()).createXQDataSource().getConnection();
		try {
			XQStaticContext staticContext = connection.getStaticContext();
			staticContext.declareNamespace(XQDataSourceFactory.XPATH_EXTENSION_NS_PREFIX, XQDataSourceFactory.XPATH_EXTENSION_NS_URI);
			connection.setStaticContext(staticContext);
			logger.info("Parsing XQuery in: " + owner.getURI());
			XQPreparedExpression preparedExpression = xQuerySource.prepareExpression(connection, owner.getParent().getURI());
			for (QName qName : preparedExpression.getAllExternalVariables()) {
				logger.info("External variable: " + qName + ", Type: " + preparedExpression.getStaticVariableType(qName));
			}
			logger.info("is result occurrence exactly one: " + (preparedExpression.getStaticResultType().getItemOccurrence() == XQSequenceType.OCC_EXACTLY_ONE));
			preparedExpression.close();
			// set modules to validated 
			for (String referenced : owner.getReferenced()) {
				owner.getArtifact(referenced).setValidated(true);
			}
		} finally {
			connection.close();
		}
	}

	@Override
	public void validateInternal(GlobalContext globalContext) throws XQException {
		validateXQuerySource(this, new XQuerySource(getContent()));
	}

}
