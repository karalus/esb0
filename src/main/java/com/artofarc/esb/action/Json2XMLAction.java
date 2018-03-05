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

import java.io.StringWriter;
import java.util.Map;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.soap.SOAPConstants;
import javax.xml.validation.Schema;

import org.eclipse.persistence.dynamic.DynamicEntity;
import org.eclipse.persistence.jaxb.JAXBContextProperties;
import org.eclipse.persistence.jaxb.UnmarshallerProperties;
import org.eclipse.persistence.oxm.MediaType;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;

public class Json2XMLAction extends TerminalAction {

	private final JAXBContext _jaxbContext;
	private final Map<String, String> _urisToPrefixes;
	private final Schema _schema;
	private final boolean _formattedOutput;

	public Json2XMLAction(JAXBContext jaxbContext, Map<String, String> urisToPrefixes, Schema schema, boolean formattedOutput) {
		_jaxbContext = jaxbContext;
		_urisToPrefixes = urisToPrefixes;
		_schema = schema;
		_formattedOutput = formattedOutput;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		String contentType = (String) message.getHeaders().get(HttpOutboundAction.HTTP_HEADER_CONTENT_TYPE);
		if (contentType != null && !contentType.startsWith(MediaType.APPLICATION_JSON.getMediaType())) {
			throw new ExecutionException(this, "Unexpected Content-Type: " + contentType);
		}
		message.getHeaders().put(HttpOutboundAction.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
		return super.prepare(context, message, inPipeline);
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		super.execute(context, execContext, message, nextActionIsPipelineStop);
		context.getTimeGauge().startTimeMeasurement();

		Unmarshaller jsonUnmarshaller = _jaxbContext.createUnmarshaller();
		jsonUnmarshaller.setProperty(UnmarshallerProperties.MEDIA_TYPE, MediaType.APPLICATION_JSON);
		jsonUnmarshaller.setProperty(UnmarshallerProperties.JSON_NAMESPACE_PREFIX_MAPPER, _urisToPrefixes);
		jsonUnmarshaller.setProperty(JAXBContextProperties.JSON_ATTRIBUTE_PREFIX, "@");
		try {
			execContext = new ExecutionContext(jsonUnmarshaller.unmarshal(message.getBodyAsSource()));
		} finally {
			context.getTimeGauge().stopTimeMeasurement("Unmarshal JSON--> Java", true);
		}
		JAXBElement<DynamicEntity> root = execContext.getResource();

		// resolve header properties
		// String senderFQN = root.getValue().get("senderFQN");
		// root.getValue().set("replyContext", senderFQN);

		Marshaller marshaller = _jaxbContext.createMarshaller();
		marshaller.setSchema(_schema);
		if (_formattedOutput) {
			marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
		}
		try {
			if (nextActionIsPipelineStop) {
				marshaller.marshal(root, message.getBodyAsSinkResult());
			} else {
				StringWriter sw = new StringWriter();
				marshaller.marshal(root, sw);
				message.reset(BodyType.STRING, sw.toString());
			}
		} catch (JAXBException e) {
			throw new ExecutionException(this, "Validation failed", e.getLinkedException());
		} finally {
			context.getTimeGauge().stopTimeMeasurement("Marshal Java --> XML", false);
		}
	}

}
