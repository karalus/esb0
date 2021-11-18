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

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.ByteArrayInputStream;
import com.artofarc.util.IOUtils;
import com.artofarc.util.StringBuilderWriter;

public class DumpAction extends TerminalAction {

	private final boolean _binary;
	private final File _dumpDir;

	public DumpAction(boolean binary, String dumpDir) {
		_binary = binary;
		if (dumpDir != null) {
			_dumpDir = new File(dumpDir);
			if (!_dumpDir.exists() || !_dumpDir.isDirectory()) {
				throw new IllegalStateException("Is not a directory " + dumpDir);
			}
		} else {
			_dumpDir = null;
		}
	}

	public DumpAction() {
		this(false, null);
	}

	@Override
	protected void execute(Context context, ESBMessage message) throws Exception {
		logESBMessage(context, message);
		if (message.getAttachments().size() > 0) {
			logger.info("Attachments: " + message.getAttachments().keySet());
		}
		if (message.getBodyType() != BodyType.INVALID) {
			if (message.getBodyType() == BodyType.EXCEPTION) {
				StringBuilderWriter writer = new StringBuilderWriter();
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
				if (_dumpDir != null) {
					File dumpFile = new File(_dumpDir, message.getVariable(ESBConstants.initialTimestamp) + ".bin");
					try (FileOutputStream fileOutputStream = new FileOutputStream(dumpFile)) {
						fileOutputStream.write(message.getBodyAsByteArray(context));
					}
					logger.info("Body dumped into " + dumpFile);
				} else if (_binary || message.isFI()) {
					ByteArrayInputStream bis = new ByteArrayInputStream(message.getBodyAsByteArray(context));
					logger.info("Body length: " + bis.length());
					logger.info("Body(" + message.getCharset() + "):\n" + IOUtils.convertToHexDump(bis));
				} else {
					logger.info("Body:\n" + message.getBodyAsString(context));
				}
			}
		}
	}

}
