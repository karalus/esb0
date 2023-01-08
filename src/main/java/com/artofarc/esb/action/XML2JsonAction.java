/*
 * Copyright 2022 Andre Karalus
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

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.json.Xml2JsonTransformer;
import static com.artofarc.esb.http.HttpConstants.*;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.StringBuilderWriter;
import com.sun.xml.xsom.XSSchemaSet;

public class XML2JsonAction extends Action {

	private static final boolean wrapperAsArrayNameDefault = Boolean.parseBoolean(System.getProperty("esb0.xml2json.wrapperAsArrayName", "true"));

	private final Xml2JsonTransformer _xml2JsonTransformer;

	public XML2JsonAction(XSSchemaSet schemaSet, String type, boolean jsonIncludeRoot, Boolean wrapperAsArrayName, Map<String, String> prefixMap) {
		_pipelineStop = true;
		_xml2JsonTransformer = new Xml2JsonTransformer(schemaSet, type, jsonIncludeRoot, wrapperAsArrayName != null ? wrapperAsArrayName : wrapperAsArrayNameDefault, prefixMap);
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		String contentType = message.getContentType();
		String type = parseContentType(contentType);
		if (isNotXML(type)) {
			throw new ExecutionException(this, "Unexpected Content-Type: " + contentType);
		}
		message.removeHeader(HTTP_HEADER_CONTENT_LENGTH);
		message.setContentType(HTTP_HEADER_CONTENT_TYPE_JSON);
		return null;
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		StringBuilderWriter sw = new StringBuilderWriter();
		message.writeToSAX(_xml2JsonTransformer.createTransformerHandler(sw), context);
		message.reset(BodyType.READER, sw.getReader());
	}

}
