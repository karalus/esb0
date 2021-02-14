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
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.mail.internet.MimeBodyPart;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.*;
import com.artofarc.util.IOUtils;

public class FileAction extends TerminalAction {

	private final File _destDir;
	private final String _verb, _filename, _append, _zip;
	private final Boolean _readable, _writable;

	public FileAction(String destDir, String verb, String filename, String append, String zip, Boolean readable, Boolean writable) throws FileNotFoundException {
		_destDir = new File(destDir);
		if (!_destDir.exists()) {
			throw new FileNotFoundException(destDir);
		}
		if (!_destDir.isDirectory()) {
			throw new IllegalStateException("Is not a directory " + destDir);
		}
		_verb = verb;
		_filename = filename;
		_append = append;
		_zip = zip;
		_readable = readable;
		_writable = writable;
	}

	@Override
	protected void execute(Context context, ESBMessage message) throws Exception {
		String contentType = HttpConstants.parseContentType(message.<String> getHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE));
		String filename = (String) bindVariable(_filename, context, message);
		String fileExtension = contentType != null ? '.' + MimeHelper.getFileExtension(contentType) : "";
		boolean zip = Boolean.parseBoolean(String.valueOf(bindVariable(_zip, context, message)));
		File file = new File(_destDir, filename + (zip ? ".zip" : fileExtension));
		String verb = (String) bindVariable(_verb, context, message);
		boolean append = false;
		switch (verb) {
		case "GET":
			message.clearHeaders();
			if (IOUtils.getExt(filename).equals("gz")) {
				filename = IOUtils.stripExt(filename);
			}
			message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, MimeHelper.guessContentTypeFromName(filename));
			message.reset(BodyType.INPUT_STREAM, new FileInputStream(file));
			break;
		case "ENTRY_MODIFY":
			append = Boolean.parseBoolean(String.valueOf(bindVariable(_append, context, message)));
		case "ENTRY_CREATE":
			if (append && zip) {
				throw new ExecutionException(this, "zip plus append is not supported, yet");
			}
			context.getTimeGauge().startTimeMeasurement();
			if (_readable != null) {
				file.setReadable(_readable, false);
			}
			if (_writable != null) {
				file.setWritable(_writable, false);
			}
			try (FileOutputStream fileOutputStream = new FileOutputStream(file, append)) {
				if (zip) {
					try (ZipOutputStream zos = new ZipOutputStream(fileOutputStream)) {
						zos.putNextEntry(new ZipEntry(filename + fileExtension));
						message.writeRawTo(zos, context);
						for (Iterator<Map.Entry<String, MimeBodyPart>> iter = message.getAttachments().entrySet().iterator(); iter.hasNext();) {
							Map.Entry<String, MimeBodyPart> entry = iter.next();
							String name = MimeHelper.getDispositionName(entry.getValue());
							zos.putNextEntry(new ZipEntry(name != null ? name : entry.getKey()));
							IOUtils.copy(entry.getValue().getInputStream(), zos);
							iter.remove();
						}
					}
				} else {
					message.writeRawTo(fileOutputStream, context);
				}
			} finally {
				context.getTimeGauge().stopTimeMeasurement("write file %s", false, file);
			}
			break;
		case "ENTRY_DELETE":
			message.getVariables().put("deleted", file.delete());
			break;
		default:
			throw new ExecutionException(this, "Verb not supported: " + verb);
		}
	}

}
