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

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.XQuerySource;
import com.artofarc.esb.resource.XQConnectionFactory;

public class XQueryArtifact extends XMLProcessingArtifact {

	final static String FILE_EXTENSION_XQUERY_MODULE = "xqm";

	public XQueryArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	@Override
	protected XQueryArtifact clone(FileSystem fileSystem, Directory parent) {
		return initClone(new XQueryArtifact(fileSystem, parent, getName()));
	}

	static void validateXQuerySource(Artifact owner, XQConnection connection, XQuerySource xQuerySource) throws XQException {
		XQPreparedExpression preparedExpression = xQuerySource.prepareExpression(connection, owner.getParent().getURI());
		for (QName qName : preparedExpression.getAllExternalVariables()) {
			logger.debug("External variable: " + qName + ", Type: " + preparedExpression.getStaticVariableType(qName));
		}
		logger.debug("is result occurrence exactly one: " + (preparedExpression.getStaticResultType().getItemOccurrence() == XQSequenceType.OCC_EXACTLY_ONE));
		preparedExpression.close();
		// set modules to validated 
		for (String referenced : owner.getReferenced()) {
			owner.getArtifact(referenced).setValidated();
		}
	}

	@Override
	public void validateInternal(GlobalContext globalContext) throws Exception {
		// Needs an individual XQConnectionFactory to track the use of modules
		XQConnectionFactory factory = XQConnectionFactory.newInstance(new ArtifactURIResolver(this));
		XQConnection connection = factory.getConnection();
		try {
			logger.info("Parsing XQuery in: " + getURI());
			validateXQuerySource(this, connection, XQuerySource.create(getContentAsBytes()));
		} finally {
			connection.close();
		}
	}

	@Override
	protected void clearContent() {
		// keep modules in cache
		if (!getExt(getName()).equals(FILE_EXTENSION_XQUERY_MODULE)) {
			super.clearContent();
		}
	}

}
