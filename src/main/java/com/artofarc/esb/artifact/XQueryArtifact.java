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

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.namespace.QName;
import javax.xml.xquery.XQConnection;
import javax.xml.xquery.XQException;
import javax.xml.xquery.XQPreparedExpression;
import javax.xml.xquery.XQSequenceType;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.resource.XQDataSourceFactory;

public class XQueryArtifact extends Artifact {

	private final List<String> _externalVariables = new ArrayList<>();
	private boolean _module;

	public XQueryArtifact(Directory parent, String name) {
		super(parent, name);
	}

	public List<String> getExternalVariables() {
		return _externalVariables;
	}

	public boolean isModule() {
		return _module;
	}

	public void setModule(boolean module) {
		_module = module;
	}

	@Override
	public XQueryArtifact clone(Directory parent) {
		XQueryArtifact clone = initClone(new XQueryArtifact(parent, getName()));
		return clone;
	}

	public String getXQuery() throws UnsupportedEncodingException {
		// TODO: UTF-8 is just an assumption
		return new String(getContent(), "UTF-8");
	}

	@Override
	public void validateInternal(GlobalContext globalContext) throws XQException {
		// Needs an individual XQDataSourceFactory to track the use of modules
		XQDataSourceFactory dataSourceFactory = new XQDataSourceFactory(globalContext) {
			@Override
			public void registerModule(XQueryArtifact xQueryArtifact) {
				addReference(xQueryArtifact);
			}
		};
		XQConnection connection = dataSourceFactory.createXQDataSource().getConnection();
		try {
			logger.info("Parsing XQuery: " + getURI());
			XQPreparedExpression preparedExpression = connection.prepareExpression(getContentAsByteArrayInputStream());
			for (QName qName : preparedExpression.getAllExternalVariables()) {
				_externalVariables.add(qName.getLocalPart());
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
