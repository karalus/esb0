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
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.json.JsonArrayBuilder;
import javax.mail.internet.MimeBodyPart;
import javax.xml.bind.DatatypeConverter;

import com.artofarc.esb.context.Context;
import static com.artofarc.esb.http.HttpConstants.*;
import com.artofarc.esb.message.*;
import com.artofarc.util.IOUtils;
import com.artofarc.util.JsonFactoryHelper;
import com.artofarc.util.FileInputStream;

public class FileAction extends TerminalAction {

	private final File _destDir;
	private final String _action, _filename, _append, _zip;
	private final boolean _mkdirs, _ownerOnly;
	private final Boolean _readable, _writable;

	public FileAction(String destDir, String action, String filename, boolean mkdirs, String append, String zip, Boolean readable, Boolean writable, boolean ownerOnly) throws IOException {
		if (destDir != null) {
			_destDir = new File(destDir);
			if (!_destDir.isAbsolute()) {
				throw new IllegalArgumentException("Dir is not absolute: " + destDir);
			}
			if (!_destDir.exists()) {
				throw new FileNotFoundException(destDir);
			}
			if (!_destDir.isDirectory()) {
				throw new IllegalStateException("Is not a directory " + destDir);
			}
		} else {
			_destDir = null;
		}
		_action = action;
		_filename = filename;
		_mkdirs = mkdirs;
		_append = append;
		_zip = zip;
		_readable = readable;
		_writable = writable;
		_ownerOnly = ownerOnly;
	}

	private void setPermissions(File file, boolean executable) throws IOException {
		if (_readable != null) {
			if (!file.setReadable(_readable, _ownerOnly)) {
				throw new IOException("Could not set read permission: " + file);
			}
			if (executable) {
				if (!file.setExecutable(_readable, _ownerOnly)) {
					throw new IOException("Could not set execute permission: " + file);
				}
			}
		}
		if (_writable != null) {
			if (!file.setWritable(_writable, _ownerOnly)) {
				throw new IOException("Could not set write permission: " + file);
			}
		}
	}

	private void mkdirs(File dir) throws IOException {
		if (dir != null && !dir.exists()) {
			mkdirs(dir.getParentFile());
			if (!dir.mkdir()) {
				throw new IOException("Could not create directory: " + dir);
			}
			setPermissions(dir, true);
		}
	}

	@Override
	protected void execute(Context context, ESBMessage message) throws Exception {
		String filename = (String) eval(_filename, context, message);
		String action = (String) eval(_action, context, message);
		if (action == null) {
			message.clearHeaders();
			File file = new File(_destDir, filename);
			if (_destDir == null && !file.isAbsolute()) {
				throw new ExecutionException(this, "Filename is not absolute: " + file);
			}
			if (file.isDirectory()) {
				JsonArrayBuilder builder = JsonFactoryHelper.JSON_BUILDER_FACTORY.createArrayBuilder();
				for (File f : file.listFiles()) {
					builder.add(JsonFactoryHelper.JSON_BUILDER_FACTORY.createObjectBuilder().add("name", f.getName())
							.add("dir", f.isDirectory()).add("length", f.length()).add("modificationTime", f.lastModified()).build());
				}
				message.reset(BodyType.JSON_VALUE, builder.build());
				message.setContentType(HTTP_HEADER_CONTENT_TYPE_JSON);
			} else {
				if (IOUtils.getExt(filename).equals("gz")) {
					filename = IOUtils.stripExt(filename);
					message.setContentEncoding("gzip");
				}
				message.setContentType(MimeHelper.guessContentTypeFromName(filename));
				message.reset(BodyType.INPUT_STREAM, new FileInputStream(file));
			}
		} else {
			String fileExtension = IOUtils.getExt(filename);
			if (fileExtension.isEmpty()) {
				String contentType = message.getHeader(HTTP_HEADER_CONTENT_TYPE);
				if (contentType == null) {
					contentType = message.getContentType();
				}
				fileExtension = contentType != null ? MimeHelper.getFileExtension(parseContentType(contentType)) : null;
			}
			if (fileExtension != null) {
				fileExtension = '.' + fileExtension;
				if (filename.endsWith(fileExtension)) {
					filename = filename.substring(0, filename.length() - fileExtension.length());
				}
			} else {
				fileExtension = "";
			}
			boolean zip = DatatypeConverter.parseBoolean(String.valueOf(eval(_zip, context, message)));
			File file = new File(_destDir, filename + (zip ? ".zip" : fileExtension));
			if (_destDir == null && !file.isAbsolute()) {
				throw new ExecutionException(this, "Filename is not absolute: " + file);
			}
			if (_mkdirs) {
				mkdirs(file.getCanonicalFile().getParentFile());
			}
			message.getVariables().put(ESBConstants.filename, file.getPath());
			boolean append = false;
			switch (action) {
			case "ENTRY_MODIFY":
				append = Boolean.parseBoolean(String.valueOf(eval(_append, context, message)));
			case "ENTRY_CREATE":
				if (append && zip) {
					throw new ExecutionException(this, "zip plus append is not supported, yet");
				}
				context.getTimeGauge().startTimeMeasurement();
				try (FileOutputStream fileOutputStream = new FileOutputStream(file, append)) {
					setPermissions(file, false);
					if (zip) {
						try (ZipOutputStream zos = new ZipOutputStream(fileOutputStream)) {
							zos.putNextEntry(new ZipEntry(new File(filename).getName() + fileExtension));
							message.writeRawTo(zos, context);
							for (Iterator<Map.Entry<String, MimeBodyPart>> iter = message.getAttachments().entrySet().iterator(); iter.hasNext();) {
								Map.Entry<String, MimeBodyPart> entry = iter.next();
								MimeBodyPart bodyPart = entry.getValue();
								filename = MimeHelper.getDispositionFilename(bodyPart);
								if (filename == null) {
									filename = MimeHelper.getDispositionName(bodyPart);
									if (filename == null) {
										filename = entry.getKey();
									}
									fileExtension = IOUtils.getExt(filename);
									if (fileExtension.isEmpty()) {
										fileExtension = MimeHelper.getFileExtension(getValueFromHttpHeader(bodyPart.getContentType()));
										if (fileExtension != null) {
											filename += '.' + fileExtension;
										}
									}
								}
								zos.putNextEntry(new ZipEntry(filename));
								bodyPart.getInputStream().transferTo(zos);
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
				if (file.isDirectory()) {
					Files.walk(file.toPath()).sorted(Comparator.reverseOrder()).forEach(p -> {
						try {
							Files.delete(p);
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
					});
				} else {
					Files.delete(file.toPath());
				}
				break;
			default:
				throw new ExecutionException(this, "Action not supported: " + action);
			}
		}
	}

}
