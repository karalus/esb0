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

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.json.JsonArrayBuilder;
import javax.servlet.http.HttpServletResponse;

import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.artifact.*;
import com.artofarc.esb.context.*;

import static com.artofarc.esb.http.HttpConstants.*;
import com.artofarc.esb.message.*;
import com.artofarc.util.ByteArrayOutputStream;
import com.artofarc.util.Collections;
import com.artofarc.util.IOUtils;
import com.artofarc.util.JsonFactoryHelper;
import com.artofarc.util.URLUtils;

public class AdminAction extends Action {

	private final String _verb, _resourceExp;

	public AdminAction(String verb, String resourceExp) {
		_verb = verb;
		_resourceExp = resourceExp;
		_pipelineStop = true;
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		String verb = (String) bindVariable(_verb, context, message);
		String resource = URLUtils.decode((String) bindVariable(_resourceExp, context, message));
		switch (verb) {
		case "GET":
			if (resource.isEmpty()) {
				String filename = message.getVariable(ESBConstants.filename);
				if (filename != null) {
					FileOutputStream fos = new FileOutputStream(filename);
					context.getGlobalContext().getFileSystem().dump(fos);
				} else {
					filename = "FileSystem-" + System.currentTimeMillis() + ".zip";
					ByteArrayOutputStream bos = new ByteArrayOutputStream();
					context.getGlobalContext().getFileSystem().dump(bos);
					message.reset(BodyType.INPUT_STREAM, bos.getByteArrayInputStream());
					message.clearHeaders();
					message.putHeader(HTTP_HEADER_CONTENT_TYPE, "application/zip");
					message.putHeader(HTTP_HEADER_CONTENT_DISPOSITION, "filename=\"" + filename + '"');
				}
			} else {
				readArtifact(context, message, resource);
			}
			break;
		case "POST":
			changeConfiguration(context, message, resource);
			break;
		case "DELETE":
			deleteArtifact(context.getGlobalContext(), message, resource);
			break;
		default:
			message.putVariable(ESBConstants.HttpResponseCode, HttpServletResponse.SC_METHOD_NOT_ALLOWED);
			throw new ExecutionException(this, verb);
		}
	}

	private void readArtifact(Context context, ESBMessage message, String resource) throws Exception {
		Artifact artifact = context.getGlobalContext().getFileSystem().getArtifact(resource);
		if (artifact != null) {
			if (artifact instanceof Directory) {
				Directory directory = (Directory) artifact;
				boolean formatLong = message.getVariables().containsKey("long");
				JsonArrayBuilder builder = JsonFactoryHelper.JSON_BUILDER_FACTORY.createArrayBuilder();
				for (Artifact a : directory.getArtifacts().values()) {
					if (formatLong) {
						builder.add(JsonFactoryHelper.JSON_BUILDER_FACTORY.createObjectBuilder().add("name", a.getName())
								.add("dir", a instanceof Directory).add("modificationTime", a.getModificationTime()).build());
					} else {
						builder.add(a.getName());
					}
				}
				message.reset(BodyType.JSON_VALUE, builder.build());
				message.clearHeaders();
				message.putHeader(HTTP_HEADER_CONTENT_TYPE, HTTP_HEADER_CONTENT_TYPE_JSON);
			} else {
				String headerAccept = message.getVariable(HTTP_HEADER_ACCEPT);
				if (headerAccept == null || headerAccept.contains("text/")) {
					InputStream contentAsStream = artifact.getContentAsStream();
					if (artifact instanceof WSDLArtifact) {
						String content = IOUtils.toString(contentAsStream, ESBMessage.CHARSET_DEFAULT);
						contentAsStream.close();
						try {
							content = (String) bindVariable(content, context, message);
						} catch (Exception e) {
							logger.warn("Could not adapt WSDL " + resource, e);
						}
						message.reset(BodyType.STRING, content);
					} else {
						message.reset(BodyType.INPUT_STREAM, contentAsStream);
					}
					message.clearHeaders();
					message.putHeader(HTTP_HEADER_CONTENT_TYPE, artifact.getContentType());
					message.putHeader(HTTP_HEADER_CONTENT_DISPOSITION, "filename=\"" + artifact.getName() + '"');
				} else {
					message.putVariable(ESBConstants.HttpResponseCode, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
					throw new ExecutionException(this, headerAccept);
				}
			}
		} else {
			message.putVariable(ESBConstants.HttpResponseCode, HttpServletResponse.SC_NOT_FOUND);
			throw new ExecutionException(this, resource);
		}
	}

	private void deleteArtifact(GlobalContext globalContext, ESBMessage message, String resource) throws Exception {
		if (globalContext.lockFileSystem()) {
			try {
				FileSystem.ChangeSet changeSet = globalContext.getFileSystem().createChangeSet(globalContext, resource);
				DeployHelper.deployChangeSet(globalContext, changeSet);
				FileSystem newFileSystem = changeSet.getFileSystem();
				globalContext.setFileSystem(newFileSystem);
				newFileSystem.writeBackChanges();
				logger.info("Artifact " +  resource + " deleted by " + message.getVariable(ESBConstants.RemoteUser));
				message.putVariable(ESBConstants.HttpResponseCode, HttpServletResponse.SC_NO_CONTENT);
				message.reset(BodyType.INVALID, null);
			} catch (ValidationException e) {
				message.putVariable(ESBConstants.HttpResponseCode, HttpServletResponse.SC_BAD_REQUEST);
				throw e;
			} finally {
				globalContext.unlockFileSystem();
			}
		}
	}

	private void changeConfiguration(Context context, ESBMessage message, String resource) throws Exception {
		GlobalContext globalContext = context.getGlobalContext();
		if (resource.isEmpty()) {
			String contentType = message.getHeader(HTTP_HEADER_CONTENT_TYPE);
			if (contentType == null || "bin".equals(MimeHelper.getFileExtension(contentType))) {
				boolean simulate = Boolean.parseBoolean(message.getHeader("simulate"));
				InputStream is = message.getBodyType() == BodyType.INPUT_STREAM ? message.<InputStream> getBody() : new ByteArrayInputStream(message.<byte[]> getBody());
				try {
					FileSystem.ChangeSet changeSet = globalContext.getFileSystem().createChangeSet(globalContext, is);
					if (simulate) {
						changeSet.getServiceArtifacts();
					} else {
						deployChangeset(globalContext, changeSet, message);
					}
				} catch (IOException e) {
					message.putVariable(ESBConstants.HttpResponseCode, HttpServletResponse.SC_BAD_REQUEST);
					throw e;
				}
			} else {
				message.putVariable(ESBConstants.HttpResponseCode, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
				throw new ExecutionException(this, contentType);
			}
		} else {
			String enable = message.getHeader("enable");
			byte[] content = message.getBodyAsByteArray(context);
			if (enable != null || content.length == 0) {
				ConsumerPort consumerPort = globalContext.getInternalService(resource);
				if (consumerPort != null) {
					// if header is missing just toggle state
					consumerPort.enable(enable != null ? Boolean.parseBoolean(enable) : !consumerPort.isEnabled());
				} else {
					Artifact artifact = globalContext.getFileSystem().getArtifact(resource);
					if (artifact instanceof DataSourceArtifact) {
						DataSourceArtifact dataSourceArtifact = (DataSourceArtifact) artifact;
						Object dataSource = globalContext.getProperty(dataSourceArtifact.getDataSourceName());
						DataSourceArtifact.softEvictConnections(dataSource);
					} else {
						message.putVariable(ESBConstants.HttpResponseCode, HttpServletResponse.SC_NOT_FOUND);
						throw new ExecutionException(this, resource);
					}
				}
				message.putVariable(ESBConstants.HttpResponseCode, HttpServletResponse.SC_NO_CONTENT);
				message.reset(BodyType.INVALID, null);
			} else {
				String filename = getFilename(message.getHeader(HTTP_HEADER_CONTENT_DISPOSITION));
				if (filename != null) {
					resource += '/' + filename;
				}
				FileSystem.ChangeSet changeSet = globalContext.getFileSystem().createChangeSet(globalContext, resource, content);
				deployChangeset(globalContext, changeSet, message);
			}
		}
	}

	private void deployChangeset(GlobalContext globalContext, FileSystem.ChangeSet changeSet, ESBMessage message) throws Exception {
		if (globalContext.lockFileSystem()) {
			try {
				int serviceCount = DeployHelper.deployChangeSet(globalContext, changeSet);
				FileSystem newFileSystem = changeSet.getFileSystem();
				globalContext.setFileSystem(newFileSystem);
				newFileSystem.writeBackChanges();
				logger.info("Configuration changed by: " + resolve(message, ESBConstants.RemoteUser, false));
				logger.info("Number of created/updated services: " + serviceCount);
				logger.info("Number of deleted services: " + Collections.typeSelect(changeSet.getDeletedArtifacts(), ServiceArtifact.class).count());
				message.putVariable(ESBConstants.HttpResponseCode, HttpServletResponse.SC_NO_CONTENT);
				message.reset(BodyType.INVALID, null);
			} catch (ValidationException | RuntimeException e) {
				message.putVariable(ESBConstants.HttpResponseCode, HttpServletResponse.SC_BAD_REQUEST);
				throw e;
			} finally {
				globalContext.unlockFileSystem();
			}
		} else {
			message.putVariable(ESBConstants.HttpResponseCode, HttpServletResponse.SC_GATEWAY_TIMEOUT);
			throw new ExecutionException(this, "Another update is in progress");
		}
		
	}

}
