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
import javax.json.JsonWriter;
import javax.servlet.http.HttpServletResponse;

import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.artifact.DeployHelper;
import com.artofarc.esb.artifact.Artifact;
import com.artofarc.esb.artifact.Directory;
import com.artofarc.esb.artifact.FileSystem;
import com.artofarc.esb.artifact.ValidationException;
import com.artofarc.esb.artifact.WSDLArtifact;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.StreamUtils;
import com.artofarc.util.StringWriter;

public class AdminAction extends Action {

	public AdminAction() {
		_pipelineStop = true;
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		String verb = resolve(message, ESBConstants.HttpMethod, true);
		String resource = resolve(message, ESBConstants.appendHttpUrlPath, true);
		switch (verb) {
		case "GET":
			BranchOnPathAction.parseQueryString(message);
			if (message.getVariables().containsKey("delete")) {
				// Dirty hack for standard JSP is not able to send DELETE without ajax
				deleteArtifact(context.getPoolContext().getGlobalContext(), message, resource);
			} else {
				readArtifact(context.getPoolContext().getGlobalContext(), message, resource);
			}
			break;
		case "PUT":
		case "POST":
			changeConfiguration(context.getPoolContext().getGlobalContext(), message, resource);
			break;
		case "DELETE":
			deleteArtifact(context.getPoolContext().getGlobalContext(), message, resource);
			break;
		default:
			createErrorResponse(message, HttpServletResponse.SC_METHOD_NOT_ALLOWED, verb);
			break;
		}
	}

	private static void createResponse(ESBMessage message, BodyType bodyType, Object body) {
		message.getHeaders().clear();
		message.putVariable(ESBConstants.HttpResponseCode, HttpServletResponse.SC_OK);
		if (body == null) message.getVariables().put(ESBConstants.redirect, message.getVariable(ESBConstants.ContextPath) + "/admin");
		message.reset(bodyType, body);
	}

	private static void createErrorResponse(ESBMessage message, int errorCode, String errorMessage) {
		message.getHeaders().clear();
		message.putVariable(ESBConstants.HttpResponseCode, errorCode);
		if (errorMessage != null) message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/plain");
		message.reset(null, errorMessage);
	}

	private void readArtifact(GlobalContext globalContext, ESBMessage message, String resource) throws IOException {
		Artifact artifact = globalContext.getFileSystem().getArtifact(resource);
		if (artifact != null) {
			if (artifact instanceof Directory) {
				Directory directory = (Directory) artifact;
				JsonArrayBuilder builder = Json.createArrayBuilder();
				for (String artifactName : directory.getArtifacts().keySet()) {
					builder.add(artifactName);
				}
				StringWriter sw = new StringWriter();
				JsonWriter jsonWriter = Json.createWriter(sw);
				jsonWriter.writeArray(builder.build());
				jsonWriter.close();
				createResponse(message, BodyType.READER, sw.getStringReader());
				message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, HttpConstants.HTTP_HEADER_CONTENT_TYPE_JSON);
			} else {
				String headerAccept = message.getVariable(HttpConstants.HTTP_HEADER_ACCEPT);
				// SoapUI does not send an "Accept" header
				if (headerAccept == null || headerAccept.contains("text/")) {
					InputStream contentAsStream = artifact.getContentAsStream();
					if (artifact instanceof WSDLArtifact) {
						String content = new String(StreamUtils.copy(contentAsStream), ESBMessage.CHARSET_DEFAULT);
						contentAsStream.close();
						try {
							content = (String) bindVariable(content, null, message);
						} catch (Exception e) {
							logger.warn("Could not adapt WSDL " + resource, e);
						}
						createResponse(message, BodyType.STRING, content);
					} else {
						createResponse(message, BodyType.INPUT_STREAM, contentAsStream);
					}
					message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, artifact.getContentType());
					message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_DISPOSITION, "filename=\"" + artifact.getName() + '"');
				} else {
					createErrorResponse(message, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, null);
				}
			}
		} else {
			createErrorResponse(message, HttpServletResponse.SC_NOT_FOUND, null);
		}
	}

	private static void deleteArtifact(GlobalContext globalContext, ESBMessage message, String resource) throws Exception {
		if (globalContext.lockFileSystem()) {
			try {
				FileSystem.ChangeSet changeSet = globalContext.getFileSystem().createChangeSet(globalContext, resource);
				DeployHelper.deployChangeSet(globalContext, changeSet);
				FileSystem newFileSystem = changeSet.getFileSystem();
				globalContext.setFileSystem(newFileSystem);
				newFileSystem.writeBackChanges();
				logger.info("Artifact " +  resource + " deleted by " + message.getVariable(ESBConstants.RemoteUser));
				createResponse(message, null, null);
			} catch (ValidationException e) {
				logger.error("Not valid", e);
				createErrorResponse(message, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
			} finally {
				globalContext.unlockFileSystem();
			}
		}
	}

	private static void changeConfiguration(GlobalContext globalContext, ESBMessage message, String resource) throws Exception {
		// if a file is received then deploy it otherwise change the state of a service flow
		if (message.getHeader(HttpConstants.HTTP_HEADER_CONTENT_DISPOSITION) != null) {
			String contentType = message.getHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE);
			if (contentType.startsWith("application/")) {
				if (globalContext.lockFileSystem()) {
					try {
						InputStream is = message.getBodyType() == BodyType.INPUT_STREAM ? message.<InputStream> getBody() : new ByteArrayInputStream(message.<byte[]> getBody());
						FileSystem.ChangeSet changeSet = globalContext.getFileSystem().createChangeSet(globalContext, is);
						int serviceCount = DeployHelper.deployChangeSet(globalContext, changeSet);
						FileSystem newFileSystem = changeSet.getFileSystem();
						globalContext.setFileSystem(newFileSystem);
						newFileSystem.writeBackChanges();
						logger.info("Configuration changed by: " + message.getVariable(ESBConstants.RemoteUser));
						logger.info("Number of created/updated services: " + serviceCount);
						logger.info("Number of deleted services: " + changeSet.getDeletedServiceArtifacts().size());
						createResponse(message, null, null);
					} catch (ValidationException e) {
						logger.error("Not valid", e);
						createErrorResponse(message, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
					} finally {
						globalContext.unlockFileSystem();
					}
				} else {
					createErrorResponse(message, HttpServletResponse.SC_GATEWAY_TIMEOUT, "Another update is in progress");
				}
			} else {
				createErrorResponse(message, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, null);
			}
		} else {
			ConsumerPort consumerPort = globalContext.getInternalService(resource);
			if (consumerPort != null) {
				String enable = message.getHeader("enable");
				// if header is missing just toggle state
				consumerPort.enable(enable != null ? Boolean.parseBoolean(enable) : !consumerPort.isEnabled());
				createResponse(message, null, null);
			} else {
				createErrorResponse(message, HttpServletResponse.SC_NOT_FOUND, null);
			}
		}
	}

}
