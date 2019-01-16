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
package com.artofarc.esb.action;

import java.util.Map;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.soap.SOAPConstants;
import javax.xml.validation.Schema;

import org.eclipse.persistence.jaxb.JAXBContextProperties;
import org.eclipse.persistence.jaxb.MarshallerProperties;
import org.eclipse.persistence.oxm.MediaType;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.StringWriter;

public class XML2JsonAction extends Action {

	private final JAXBContext _jaxbContext;
	private final Map<String, String> _urisToPrefixes;
	private final Schema _schema;
	private final boolean _formattedOutput;

	public XML2JsonAction(JAXBContext jaxbContext, Map<String, String> urisToPrefixes, Schema schema, boolean formattedOutput) {
		_jaxbContext = jaxbContext;
		_urisToPrefixes = urisToPrefixes;
		_schema = schema;
		_formattedOutput = formattedOutput;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		String contentType = message.getHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE);
		if (contentType != null && !contentType.startsWith(SOAPConstants.SOAP_1_1_CONTENT_TYPE)) {
			throw new ExecutionException(this, "Unexpected Content-Type: " + contentType);
		}
		message.getHeaders().clear();
		message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, HttpConstants.HTTP_HEADER_CONTENT_TYPE_JSON);
		context.getTimeGauge().startTimeMeasurement();
		Unmarshaller unmarshaller = _jaxbContext.createUnmarshaller();
		unmarshaller.setSchema(_schema);
		try {
			return new ExecutionContext(unmarshaller.unmarshal(message.getBodyAsXMLStreamReader(context)));
		} finally {
			context.getTimeGauge().stopTimeMeasurement("Unmarshal XML--> Java", true);
		}
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		Object root = execContext.getResource();

		// TOREVIEW: synchronized?
		if (_urisToPrefixes.isEmpty()) {
			if (root instanceof JAXBElement) {
				JAXBElement<?> jaxbElement = (JAXBElement<?>) root;
				_urisToPrefixes.put(jaxbElement.getName().getNamespaceURI(), "");
			}
		}

		Marshaller jsonMarshaller = _jaxbContext.createMarshaller();
		jsonMarshaller.setProperty(MarshallerProperties.MEDIA_TYPE, MediaType.APPLICATION_JSON);
		jsonMarshaller.setProperty(MarshallerProperties.NAMESPACE_PREFIX_MAPPER, _urisToPrefixes);
		jsonMarshaller.setProperty(JAXBContextProperties.JSON_ATTRIBUTE_PREFIX, "@");
		if (_formattedOutput) {
			jsonMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
		}
		if (message.isSink()) {
			jsonMarshaller.marshal(root, message.getBodyAsSinkResult(context));
		} else {
			StringWriter sw = new StringWriter(ESBMessage.MTU);
			jsonMarshaller.marshal(root, sw);
			message.reset(BodyType.READER, sw.getStringReader());
		}
		context.getTimeGauge().stopTimeMeasurement("Marshal Java --> JSON", false);
	}

}
