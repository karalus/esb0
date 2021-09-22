/*
 * Copyright 2021 Andre Karalus
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.artofarc.esb.artifact;

import javax.xml.xquery.XQConnection;
import javax.xml.xquery.XQException;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.resource.XQConnectionFactory;
import com.artofarc.util.XQuerySource;

public class XQueryArtifact extends XMLProcessingArtifact {

	public XQueryArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	@Override
	protected XQueryArtifact clone(FileSystem fileSystem, Directory parent) {
		return initClone(new XQueryArtifact(fileSystem, parent, getName()));
	}

	static void validateXQuerySource(Artifact owner, XQConnectionFactory factory, XQuerySource xQuerySource) throws Exception {
		ValidationErrorListener errorListener = new ValidationErrorListener(owner.getURI());
		factory.setErrorListener(errorListener);
		XQConnection connection = factory.getConnection();
		try {
			xQuerySource.prepareExpression(connection, owner.getURI()).close();
		} catch (XQException e) {
			throw errorListener.build(e);
		} catch (StackOverflowError e) {
			// StackOverflowError can happen when a XQuery is deeply nested
			throw new ValidationException(owner, e);
		} finally {
			connection.close();
		}
		// set modules to validated 
		for (String referenced : owner.getReferenced()) {
			Artifact referencedArtifact = owner.getArtifact(referenced);
			if (referencedArtifact instanceof XMLProcessingArtifact) {
				referencedArtifact.setValidated();
			}
		}
	}

	@Override
	public void validateInternal(GlobalContext globalContext) throws Exception {
		// Needs an individual XQConnectionFactory to track the use of modules
		XQConnectionFactory factory = XQConnectionFactory.newInstance(new ArtifactURIResolver(this));
		logger.info("Parsing XQuery in: " + getURI());
		validateXQuerySource(this, factory, XQuerySource.create(getContentAsBytes()));
	}

}
