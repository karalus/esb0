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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.ESBVariableConstants;

public class FileAction extends TerminalAction {
	
	private final File _destDir;

	public FileAction(String destDir) throws IOException {
		_destDir = new File(destDir);
		if (!_destDir.exists()) {
			throw new FileNotFoundException(destDir);
		}
		if (!_destDir.isDirectory()) {
			throw new IllegalStateException("Is not a directory " + destDir);
		}
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		super.execute(context, execContext, message, nextActionIsPipelineStop);
		File file = new File(_destDir, message.<String>getVariable(ESBVariableConstants.PathInfo));
		String method = message.getVariable(ESBVariableConstants.HttpMethod);
		switch (method) {
		case "ENTRY_CREATE":
		case "ENTRY_MODIFY":
			FileOutputStream fileOutputStream = new FileOutputStream(file);
			context.getTimeGauge().startTimeMeasurement();
			message.writeRawTo(fileOutputStream, context);
			fileOutputStream.close();
			context.getTimeGauge().stopTimeMeasurement("write file %s", false, file);
			break;
		case "ENTRY_DELETE":
			message.getVariables().put("deleted", file.delete());
			break;
		default:
			throw new ExecutionException(this, "Method not supported: " + method);
		}
	}

}
