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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.servlet.http.HttpServletResponse;

import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.artifact.*;
import com.artofarc.esb.context.*;
import static com.artofarc.esb.http.HttpConstants.*;
import com.artofarc.esb.message.*;
import com.artofarc.util.IOUtils;

public class AdminAction extends Action {

	private final String _resourceExp;

	public AdminAction(String resourceExp) {
		_resourceExp = resourceExp;
		_pipelineStop = true;
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		String resource = (String) bindVariable(_resourceExp, context, message);
		String verb = resolve(message, ESBConstants.HttpMethod, true);
		switch (verb) {
		case "GET":
			readArtifact(context, message, resource);
			break;
		case "PUT":
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

	private void readArtifact(Context context, ESBMessage message, String resource) throws IOException, ExecutionException {
		Artifact artifact = context.getGlobalContext().getFileSystem().getArtifact(resource);
		if (artifact != null) {
			message.clearHeaders();
			if (artifact instanceof Directory) {
				Directory directory = (Directory) artifact;
				JsonArrayBuilder builder = Json.createArrayBuilder();
				for (String artifactName : directory.getArtifacts().keySet()) {
					builder.add(artifactName);
				}
				message.reset(BodyType.STRING, builder.build().toString());
				message.putHeader(HTTP_HEADER_CONTENT_TYPE, HTTP_HEADER_CONTENT_TYPE_JSON);
			} else {
				String headerAccept = message.getVariable(HTTP_HEADER_ACCEPT);
				if (headerAccept == null || headerAccept.contains("text/")) {
					InputStream contentAsStream = artifact.getContentAsStream();
					if (artifact instanceof WSDLArtifact) {
						String content = new String(IOUtils.copy(contentAsStream), ESBMessage.CHARSET_DEFAULT);
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
			if (contentType != null && "bin".equals(MimeHelper.getFileExtension(contentType))) {
				InputStream is = message.getBodyType() == BodyType.INPUT_STREAM ? message.<InputStream> getBody() : new ByteArrayInputStream(message.<byte[]> getBody());
				FileSystem.ChangeSet changeSet = globalContext.getFileSystem().createChangeSet(globalContext, is);
				deployChangeset(globalContext, changeSet, message);
			} else {
				message.putVariable(ESBConstants.HttpResponseCode, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
				throw new ExecutionException(this, contentType);
			}
		} else {
			String enable = message.getHeader("enable");
			String content = message.getBodyAsString(context);
			if (enable != null || content.isEmpty()) {
				ConsumerPort consumerPort = globalContext.getInternalService(resource);
				if (consumerPort != null) {
					// if header is missing just toggle state
					consumerPort.enable(enable != null ? Boolean.parseBoolean(enable) : !consumerPort.isEnabled());
					message.putVariable(ESBConstants.HttpResponseCode, HttpServletResponse.SC_NO_CONTENT);
					message.reset(BodyType.INVALID, null);
				} else {
					message.putVariable(ESBConstants.HttpResponseCode, HttpServletResponse.SC_NOT_FOUND);
					throw new ExecutionException(this, resource);
				}
			} else {
				FileSystem.ChangeSet changeSet = globalContext.getFileSystem().createChangeSet(globalContext, resource, content.getBytes());
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
				logger.info("Configuration changed by: " + message.getVariable(ESBConstants.RemoteUser));
				logger.info("Number of created/updated services: " + serviceCount);
				logger.info("Number of deleted services: " + changeSet.getDeletedServiceArtifacts().size());
				message.putVariable(ESBConstants.HttpResponseCode, HttpServletResponse.SC_NO_CONTENT);
				message.reset(BodyType.INVALID, null);
			} catch (ValidationException e) {
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
