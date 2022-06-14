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
package com.artofarc.esb.action;

import java.util.Map;

import javax.xml.transform.sax.SAXSource;
import javax.xml.xquery.XQItem;

import org.xml.sax.XMLReader;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import static com.artofarc.esb.http.HttpConstants.*;
import com.artofarc.esb.json.Json2XmlTransformer;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.XMLFilterBase;
import com.sun.xml.xsom.XSSchemaSet;

public class Json2XMLAction extends SAXAction {

	private static final boolean useStreaming = Boolean.parseBoolean(System.getProperty("esb0.json2xml.streaming", "true"));

	private final Json2XmlTransformer _json2xml; 
	private final boolean _streaming;

	public Json2XMLAction(XSSchemaSet schemaSet, String type, boolean jsonIncludeRoot, String xmlElement, Map<String, String> prefixMap, Boolean streaming) {
		_streaming = streaming != null ? streaming : useStreaming;
		_json2xml = new Json2XmlTransformer(schemaSet, true, xmlElement, type, jsonIncludeRoot, prefixMap);
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		String contentType = message.removeHeader(HTTP_HEADER_CONTENT_TYPE);
		if (isNotJSON(contentType)) {
			throw new ExecutionException(this, "Unexpected Content-Type: " + contentType);
		}
		message.putHeader(HTTP_HEADER_CONTENT_TYPE, SOAP_1_1_CONTENT_TYPE);
		if (message.getBodyType() == BodyType.JSON_VALUE) {
			SAXSource source = new SAXSource(_json2xml.createParser(message.getBody()), null);
			message.reset(BodyType.SOURCE, source);
			return new ExecutionContext(source);
		} else {
			return super.prepare(context, message, inPipeline);
		}
	}

	@Override
	protected SAXSource createSAXSource(Context context, ESBMessage message, XQItem item) throws Exception {
		throw new ExecutionException(this, "JSON expected, got XQItem");
	}

	@Override
	protected XMLFilterBase createXMLFilter(Context context, ESBMessage message, XMLReader parent) throws Exception {
		if (parent != null) {
			throw new ExecutionException(this, "JSON expected: parent must be null");
		}
		return _streaming ? _json2xml.createStreamingParser() : _json2xml.createParser();
	}

}
