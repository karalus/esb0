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

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.transform.stream.StreamSource;

import org.eclipse.persistence.jaxb.dynamic.DynamicJAXBContextFactory;

import com.artofarc.esb.context.GlobalContext;

public class XSDArtifact extends SchemaArtifact {

	public XSDArtifact(Directory parent, String name) {
		super(parent, name);
	}

	@Override
	public XSDArtifact clone(Directory parent) {
		XSDArtifact clone = initClone(new XSDArtifact(parent, getName()));
		clone._jaxbContext = _jaxbContext;
		clone.schema = schema;
		return clone;
	}

	public JAXBContext getJAXBContext() throws JAXBException {
		if (_jaxbContext == null) {
			StreamSource streamSource = new StreamSource(getContentAsByteArrayInputStream());
			streamSource.setSystemId(getURI());
			_jaxbContext = DynamicJAXBContextFactory.createContextFromXSD(streamSource, this, null, null);
		}
		return _jaxbContext;
	}

	@Override
	public void validateInternal(GlobalContext globalContext) throws Exception {
		schema = getSchemaFactory().newSchema(new StreamSource(getContentAsByteArrayInputStream()));
		validateReferenced(globalContext);
	}

}
