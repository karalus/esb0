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

import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.namespace.QName;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.validation.Schema;

import org.eclipse.persistence.dynamic.DynamicEntity;
import org.eclipse.persistence.jaxb.UnmarshallerProperties;
import org.eclipse.persistence.jaxb.dynamic.DynamicJAXBContext;
import org.eclipse.persistence.oxm.MediaType;
import org.xml.sax.InputSource;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.json.Json2XmlTransformer;

import static com.artofarc.esb.http.HttpConstants.*;

import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.Collections;
import com.artofarc.util.StringWriter;
import com.sun.xml.xsom.XSSchemaSet;

public class Json2XMLAction extends Action {

	static final boolean useMOXy = Boolean.parseBoolean(System.getProperty("esb0.moxy", "true"));	

	private final DynamicJAXBContext _jaxbContext;
	private final Json2XmlTransformer _json2xml; 
	private final Class<DynamicEntity> _type;
	private final Boolean _jsonIncludeRoot, _caseInsensitive;
	private final Map<String, String> _urisToPrefixes;
	private final Schema _schema;
	private final Boolean _formattedOutput;
	private final QName _xmlElement;

	@SuppressWarnings("unchecked")
	public Json2XMLAction(DynamicJAXBContext jaxbContext, XSSchemaSet schemaSet, String type, boolean jsonIncludeRoot, boolean caseInsensitive, String xmlElement, Map<String, String> prefixMap, Schema schema, boolean formattedOutput) {
		_pipelineStop = useMOXy;
		_jaxbContext = jaxbContext;
		if (type != null) {
			QName qName = QName.valueOf(type);
			Object object = _jaxbContext.createByQualifiedName(qName.getNamespaceURI(), qName.getLocalPart(), true);
			if (object == null) {
				throw new IllegalArgumentException("Type not found: " + type);
			}
			_type = (Class<DynamicEntity>) object.getClass();
		} else {
			_type = null;
		}
		_xmlElement = xmlElement != null ? QName.valueOf(xmlElement) : null;
		_jsonIncludeRoot = jsonIncludeRoot;
		_caseInsensitive = caseInsensitive;
		_urisToPrefixes = Collections.inverseMap(prefixMap.entrySet(), useMOXy);
		_schema = schema;
		_formattedOutput = formattedOutput;
		_json2xml = new Json2XmlTransformer(schemaSet, true, xmlElement, jsonIncludeRoot, prefixMap);
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		String contentType = message.removeHeader(HTTP_HEADER_CONTENT_TYPE);
		if (contentType != null && !contentType.startsWith(HTTP_HEADER_CONTENT_TYPE_JSON)) {
			throw new ExecutionException(this, "Unexpected Content-Type: " + contentType);
		}
		message.putHeader(HTTP_HEADER_CONTENT_TYPE, SOAP_1_1_CONTENT_TYPE);
		if (useMOXy) {
			return null;
		} else {
			InputSource inputSource = SAXSource.sourceToInputSource(message.getBodyAsSource(context));
			if (inputSource == null) {
				throw new IllegalStateException("Message is invalid");			
			}
			SAXSource source = new SAXSource(_json2xml.createParser(), inputSource);
			message.reset(BodyType.SOURCE, source);
			return new ExecutionContext(source);
		}
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		if (useMOXy) {
			context.getTimeGauge().startTimeMeasurement();
			Unmarshaller jsonUnmarshaller = _jaxbContext.createUnmarshaller();
			jsonUnmarshaller.setProperty(UnmarshallerProperties.MEDIA_TYPE, MediaType.APPLICATION_JSON);
			jsonUnmarshaller.setProperty(UnmarshallerProperties.JSON_INCLUDE_ROOT, _jsonIncludeRoot);
			jsonUnmarshaller.setProperty(UnmarshallerProperties.UNMARSHALLING_CASE_INSENSITIVE, _caseInsensitive);
			jsonUnmarshaller.setProperty(UnmarshallerProperties.JSON_NAMESPACE_PREFIX_MAPPER, _urisToPrefixes);
			jsonUnmarshaller.setProperty(UnmarshallerProperties.JSON_ATTRIBUTE_PREFIX, "@");
			Object root;
			try {
				if (_type != null) {
					JAXBElement<DynamicEntity> jaxbElement = jsonUnmarshaller.unmarshal(message.getBodyAsSource(context), _type);
					if (_xmlElement != null) {
						root = new JAXBElement<>(_xmlElement, _type, jaxbElement.getValue());
					} else {
						root = jaxbElement;
					}
				} else {
					root = jsonUnmarshaller.unmarshal(message.getBodyAsSource(context));
				}
			} finally {
				context.getTimeGauge().stopTimeMeasurement("Unmarshal JSON --> Java", true);
			}
			Marshaller marshaller = _jaxbContext.createMarshaller();
			if (_type == null) {
				marshaller.setSchema(_schema);
			}
			try {
				if (nextActionIsPipelineStop) {
					marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, _formattedOutput);
					marshaller.marshal(root, message.getBodyAsSinkResult(context));
				} else {
					message.marshal(context, marshaller, root);
				}
			} catch (JAXBException e) {
				throw new ExecutionException(this, "Validation failed", e.getLinkedException());
			} finally {
				context.getTimeGauge().stopTimeMeasurement("Marshal Java --> XML", false);
			}
		} else {
			if (nextActionIsPipelineStop) {
				if (message.isSink()) {
					context.getIdenticalTransformer().transform(execContext.<SAXSource> getResource(), message.getBodyAsSinkResult(context));
				} else {
					StringWriter sw = new StringWriter();
					context.getIdenticalTransformer().transform(execContext.<SAXSource> getResource(), new StreamResult(sw));
					message.reset(BodyType.READER, sw.getStringReader());
					//message.reset(BodyType.STRING, sw.toString());
				}
			}
		}
	}

}
