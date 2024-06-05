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

import jakarta.json.JsonArrayBuilder;

import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.artifact.*;
import com.artofarc.esb.context.*;
import static com.artofarc.esb.http.HttpConstants.*;
import com.artofarc.esb.jms.JMSConsumer;
import com.artofarc.esb.message.*;
import com.artofarc.util.ByteArrayOutputStream;
import com.artofarc.util.DataStructures;
import com.artofarc.util.IOUtils;
import com.artofarc.util.JsonFactoryHelper;
import com.artofarc.util.ReflectionUtils;
import com.artofarc.util.URLUtils;

public class AdminAction extends Action {

	// Copied from HttpServletResponse to be independent from servlet container
	public static final int SC_NO_CONTENT = 204;
	public static final int SC_BAD_REQUEST = 400;
	public static final int SC_NOT_FOUND = 404;
	public static final int SC_METHOD_NOT_ALLOWED = 405;
	public static final int SC_NOT_ACCEPTABLE = 406;
	public static final int SC_UNSUPPORTED_MEDIA_TYPE = 415;
	public static final int SC_INTERNAL_SERVER_ERROR = 500;
	public static final int SC_GATEWAY_TIMEOUT = 504;

	private final String _verb, _resourceExp;

	public AdminAction(String verb, String resourceExp) {
		_verb = verb;
		_resourceExp = resourceExp;
		_pipelineStop = true;
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		String verb = (String) eval(_verb, context, message);
		String resource = (String) eval(_resourceExp, context, message);
		if (resource == null) {
			throw new ExecutionException(this, _resourceExp + " must not evaluate to null");
		}
		resource = URLUtils.decode(resource);
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
					message.setContentType("application/zip");
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
			throwHttpError(message, SC_METHOD_NOT_ALLOWED, new ExecutionException(this, verb));
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
				message.setContentType(HTTP_HEADER_CONTENT_TYPE_JSON);
			} else {
				String headerAccept = message.getVariable(HTTP_HEADER_ACCEPT);
				if (headerAccept == null || isAcceptable(headerAccept, artifact.getContentType())) {
					InputStream contentAsStream = artifact.getContentAsStream();
					if (artifact instanceof WSDLArtifact) {
						String content = IOUtils.toString(contentAsStream, artifact.getEncoding());
						contentAsStream.close();
						try {
							content = (String) eval(content, context, message);
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
					message.setSinkEncoding(artifact.getEncoding());
				} else {
					throwHttpError(message, SC_NOT_ACCEPTABLE, new ExecutionException(this, headerAccept));
				}
			}
		} else {
			throwHttpError(message, SC_NOT_FOUND, new ExecutionException(this, resource));
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
				logger.info("Artifact " + resource + " deleted by " + message.getVariable(ESBConstants.RemoteUser));
				message.putVariable(ESBConstants.HttpResponseCode, SC_NO_CONTENT);
				message.reset(BodyType.INVALID, null);
			} catch (ValidationException e) {
				throwHttpError(message, SC_BAD_REQUEST, e);
			} finally {
				globalContext.unlockFileSystem();
			}
		}
	}

	private void changeConfiguration(Context context, ESBMessage message, String resource) throws Exception {
		GlobalContext globalContext = context.getGlobalContext();
		if (resource.isEmpty()) {
			String contentType = message.getContentType();
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
				} catch (IOException | ValidationException e) {
					throwHttpError(message, SC_BAD_REQUEST, e);
				}
			} else {
				throwHttpError(message, SC_UNSUPPORTED_MEDIA_TYPE, new ExecutionException(this, contentType));
			}
		} else {
			String enableStr = (String) resolve(message, "enable", true);
			if (enableStr != null || message.isEmpty()) {
				ConsumerPort consumerPort = globalContext.getInternalService(resource);
				if (consumerPort != null) {
					// if header is missing just toggle state
					boolean enable = enableStr != null ? Boolean.parseBoolean(enableStr) : !consumerPort.isEnabled();
					if (consumerPort instanceof JMSConsumer) {
						String key = (String) resolve(message, "key", true);
						for (JMSConsumer jmsConsumer : ((JMSConsumer) consumerPort).getGroup()) {
							if (key == null || jmsConsumer.getKey().equals(key)) {
								jmsConsumer.enable(enable);
							}
						}
					} else {
						consumerPort.enable(enable);
					}
				} else {
					Artifact artifact = globalContext.getFileSystem().getArtifact(resource);
					if (artifact instanceof JNDIObjectFactoryArtifact) {
						JNDIObjectFactoryArtifact jndiObjectFactoryArtifact = (JNDIObjectFactoryArtifact) artifact;
						if (jndiObjectFactoryArtifact.getAdminPostAction() != null) {
							Object object = globalContext.getProperty(jndiObjectFactoryArtifact.getJndiName());
							try {
								ReflectionUtils.eval(object, jndiObjectFactoryArtifact.getAdminPostAction());
							} catch (Exception e) {
								throwHttpError(message, SC_INTERNAL_SERVER_ERROR, new ExecutionException(this, "Could not eval " + jndiObjectFactoryArtifact.getAdminPostAction(), e));
							}
						} else {
							throwHttpError(message, SC_METHOD_NOT_ALLOWED, new ExecutionException(this, resource));
						}
					} else {
						throwHttpError(message, SC_NOT_FOUND, new ExecutionException(this, resource));
					}
				}
				message.putVariable(ESBConstants.HttpResponseCode, SC_NO_CONTENT);
				message.reset(BodyType.INVALID, null);
			} else {
				String filename = getFilename(message.getHeader(HTTP_HEADER_CONTENT_DISPOSITION));
				if (filename != null) {
					resource += '/' + filename;
				}
				FileSystem.ChangeSet changeSet = globalContext.getFileSystem().createChangeSet(globalContext, resource, message.getBodyAsByteArray(context));
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
				logger.info("Number of deleted services: " + DataStructures.typeSelect(changeSet.getDeletedArtifacts(), ServiceArtifact.class).count());
				message.putVariable(ESBConstants.HttpResponseCode, SC_NO_CONTENT);
				message.reset(BodyType.INVALID, null);
			} catch (ValidationException | RuntimeException e) {
				throwHttpError(message, SC_BAD_REQUEST, e);
			} finally {
				globalContext.unlockFileSystem();
			}
		} else {
			throwHttpError(message, SC_GATEWAY_TIMEOUT, new ExecutionException(this, "Another update is in progress"));
		}
		
	}

	private static void throwHttpError(ESBMessage message, int statusCode, Exception e) throws Exception {
		message.putVariable(ESBConstants.HttpResponseCode, statusCode);
		throw e;
	}

}
