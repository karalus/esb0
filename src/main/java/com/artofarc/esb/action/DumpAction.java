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

import java.io.PrintWriter;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.StreamUtils;
import com.artofarc.util.StringWriter;

public class DumpAction extends TerminalAction {

	private final boolean _binary;

	public DumpAction(boolean binary) {
		_binary = binary;
	}

	public DumpAction() {
		this(false);
	}

	@Override
	protected void execute(Context context, ExecutionContext resource, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		super.execute(context, resource, message, nextActionIsPipelineStop);
		StringWriter writer = new StringWriter();
		writer.write("Headers: ");
		ESBMessage.dumpKeyValues(context, message.getHeaders(), writer);
		logger.info(writer.toString());
		writer.reset();
		writer.write("Variables: ");
		ESBMessage.dumpKeyValues(context, message.getVariables().entrySet(), writer);
		logger.info(writer.toString());
		writer.reset();
		if (message.getBodyType() != BodyType.INVALID) {
			if (message.getBodyType() == BodyType.EXCEPTION) {
				writer.write("Body(Exception): ");
				message.<Exception> getBody().printStackTrace(new PrintWriter(writer));
				logger.info(writer.toString());
			} else {
				if (message.isStream()) {
					// Materialize message in case it is a stream thus it will not be consumed
					if (message.getBodyType().hasCharset()) {
						message.getBodyAsByteArray(context);
					} else {
						message.getBodyAsString(context);
					}
				}
				if (_binary) {
					logger.info("Body(" + message.getCharset() + "):\n" + StreamUtils.convertToHexDump(message.getBodyAsInputStream(context)));
				} else {
					logger.info("Body:\n" + message.getBodyAsString(context));
				}
			}
		}
	}

}
