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

import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.namespace.QName;
import javax.xml.validation.Schema;

import org.eclipse.persistence.jaxb.JAXBContextProperties;
import org.eclipse.persistence.jaxb.MarshallerProperties;
import org.eclipse.persistence.jaxb.dynamic.DynamicJAXBContext;
import org.eclipse.persistence.oxm.MediaType;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import static com.artofarc.esb.http.HttpConstants.*;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.StringWriter;

public class XML2JsonAction extends Action {

	private final DynamicJAXBContext _jaxbContext;
	private final Class<?> _type;
	private final Map<String, String> _urisToPrefixes;
	private final Schema _schema;
	private final boolean _formattedOutput;

	public XML2JsonAction(DynamicJAXBContext jaxbContext, String type, Map<String, String> urisToPrefixes, Schema schema, boolean formattedOutput) {
		_jaxbContext = jaxbContext;
		if (type != null) {
			QName qName = QName.valueOf(type);
			Object object = _jaxbContext.createByQualifiedName(qName.getNamespaceURI(), qName.getLocalPart(), true);
			if (object == null) {
				throw new IllegalArgumentException("Type not found: " + type);
			}
			_type = object.getClass();
		} else {
			_type = null;
		}
		_urisToPrefixes = urisToPrefixes;
		_schema = schema;
		_formattedOutput = formattedOutput;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		String contentType = message.getHeader(HTTP_HEADER_CONTENT_TYPE);
		if (contentType != null && !contentType.startsWith(SOAP_1_1_CONTENT_TYPE)) {
			throw new ExecutionException(this, "Unexpected Content-Type: " + contentType);
		}
		message.getHeaders().clear();
		message.getHeaders().put(HTTP_HEADER_CONTENT_TYPE, HTTP_HEADER_CONTENT_TYPE_JSON);
		context.getTimeGauge().startTimeMeasurement();
		Unmarshaller unmarshaller = _jaxbContext.createUnmarshaller();
		try {
			if (_type != null) {
				return new ExecutionContext(unmarshaller.unmarshal(message.getBodyAsXMLStreamReader(context), _type));
			} else {
				unmarshaller.setSchema(_schema);
				return new ExecutionContext(unmarshaller.unmarshal(message.getBodyAsXMLStreamReader(context)));
			}
		} finally {
			context.getTimeGauge().stopTimeMeasurement("Unmarshal XML--> Java", true);
		}
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		Marshaller jsonMarshaller = _jaxbContext.createMarshaller();
		jsonMarshaller.setProperty(MarshallerProperties.MEDIA_TYPE, MediaType.APPLICATION_JSON);
		jsonMarshaller.setProperty(MarshallerProperties.NAMESPACE_PREFIX_MAPPER, _urisToPrefixes);
		jsonMarshaller.setProperty(JAXBContextProperties.JSON_ATTRIBUTE_PREFIX, "@");
		if (_formattedOutput) {
			jsonMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
		}
		Object root = execContext.getResource();
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
