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
package com.artofarc.esb.servlet;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonWriter;
import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.TimerService;
import com.artofarc.esb.artifact.Artifact;
import com.artofarc.esb.artifact.Directory;
import com.artofarc.esb.artifact.FileSystem;
import com.artofarc.esb.artifact.ServiceArtifact;
import com.artofarc.esb.artifact.ValidationException;
import com.artofarc.esb.artifact.WorkerPoolArtifact;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.PoolContext;
import com.artofarc.esb.context.WorkerPool;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.jms.JMSConsumer;
import com.artofarc.util.Closer;
import com.artofarc.util.StreamUtils;

@WebServlet("/admin/deploy/*")
@MultipartConfig
public class DeployServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	
	public static final String SERVLET_PATH = "/admin/deploy";

	private GlobalContext getGlobalContext() {
		PoolContext poolContext = (PoolContext) getServletContext().getAttribute(ESBServletContextListener.POOL_CONTEXT);
		return poolContext.getGlobalContext();
	}
	
	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		Artifact artifact = getGlobalContext().getFileSystem().getArtifact(req.getPathInfo());
		if (artifact != null) {
			if (artifact instanceof Directory) {
				Directory directory = (Directory) artifact;
				JsonArrayBuilder builder = Json.createArrayBuilder();
				for (String artifactName : directory.getArtifacts().keySet()) {
					builder.add(artifactName);
				}
				resp.setContentType(HttpConstants.HTTP_HEADER_CONTENT_TYPE_JSON);
				JsonWriter jsonWriter = Json.createWriter(resp.getOutputStream());
				jsonWriter.writeArray(builder.build());
				jsonWriter.close();
			} else {
				String headerAccept = req.getHeader(HttpConstants.HTTP_HEADER_ACCEPT);
				// SoapUI does not send an "Accept" header
				if (headerAccept == null || headerAccept.contains("text/")) {
					resp.setContentType("text/plain");
					resp.setHeader(HttpConstants.HTTP_HEADER_CONTENT_DISPOSITION, "filename=\"" + artifact.getName() + '"');
					try (InputStream contentAsStream = artifact.getContentAsStream()) {
						StreamUtils.copy(contentAsStream, resp.getOutputStream());
					}
				} else {
					resp.sendError(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
				}
			}
		} else {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND);
		}
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		GlobalContext globalContext = getGlobalContext();
		if (req.getContentType().startsWith("multipart/")) {
			Part filePart = req.getPart("file"); // Retrieves <input type="file" name="file">
			if (filePart != null) {
				log("Content Type is: " + filePart.getContentType());
				if (!filePart.getContentType().startsWith("application/")) {
					resp.sendError(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
					return;
				}
				if (globalContext.lockFileSystem()) {
					try {
						FileSystem.ChangeSet changeSet = globalContext.getFileSystem().createUpdate(globalContext, filePart.getInputStream());
						FileSystem newFileSystem = changeSet.getFileSystem();
						File anchorDir = globalContext.getFileSystem().getAnchorDir();
						deployChangeSet(globalContext, changeSet);
						globalContext.setFileSystem(newFileSystem);
						newFileSystem.writeBack(anchorDir);
					} catch (ValidationException e) {
						log("Not valid", e);
						resp.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
						return;
					} finally {
						globalContext.unlockFileSystem();
					}
				} else {
					resp.sendError(HttpServletResponse.SC_GATEWAY_TIMEOUT, "Another update is in progress");
					return;
				}
			}
		} else {
			ConsumerPort consumerPort = globalContext.getInternalService(req.getPathInfo());
			if (consumerPort != null) {
				try {
					consumerPort.enable(!consumerPort.isEnabled());
				} catch (Exception e) {
					throw new ServletException(e);
				}
			} else {
				resp.sendError(HttpServletResponse.SC_NOT_FOUND);
				return;
			}
		}
		resp.sendRedirect(req.getContextPath() + "/admin");
	}

	public static void deployChangeSet(GlobalContext globalContext, FileSystem.ChangeSet updateSet) throws ValidationException {
		ArrayList<ServiceArtifact> serviceArtifacts = updateSet.getServiceArtifacts();
		Closer closer = new Closer(globalContext.getDefaultWorkerPool().getExecutorService());
		for (WorkerPoolArtifact workerPoolArtifact : updateSet.getWorkerPoolArtifacts()) {
			String name = WorkerPoolArtifact.stripExt(workerPoolArtifact.getURI());
			com.artofarc.esb.service.WorkerPool wpDef = workerPoolArtifact.getWorkerPool();
			WorkerPool workerPool = new WorkerPool(globalContext, name, wpDef.getMinThreads(), wpDef.getMaxThreads(), wpDef.getPriority(), wpDef.getQueueDepth(), wpDef.getScheduledThreads(), wpDef.isAllowCoreThreadTimeOut());
			WorkerPool oldWorkerPool = globalContext.putWorkerPool(name, workerPool);
			if (oldWorkerPool != null) {
				// close later
				closer.add(oldWorkerPool);
			}
		}
		for (ServiceArtifact service : serviceArtifacts) {
			switch (service.getProtocol()) {
			case HTTP:
				HttpConsumer httpConsumer = service.getConsumerPort();
				HttpConsumer oldHttpConsumer = globalContext.bindHttpService(httpConsumer.getBindPath(), httpConsumer);
				if (oldHttpConsumer != null) {
					closer.closeAsync(oldHttpConsumer);
				}
				break;
			case JMS:
				JMSConsumer jmsConsumer = service.getConsumerPort();
				JMSConsumer oldConsumer = globalContext.bindJmsConsumer(jmsConsumer);
				if (oldConsumer != null) {
					closer.closeAsync(oldConsumer);
				}
				try {
					jmsConsumer.init(globalContext);
				} catch (Exception e) {
					throw new RuntimeException("Could not create JMSConsumer: " + jmsConsumer.getKey(), e);
				}
				break;
			case TIMER:
				TimerService timerService = service.getConsumerPort();
				TimerService oldTimerService = globalContext.bindTimerService(timerService);
				if (oldTimerService != null) {
					closer.closeAsync(oldTimerService);
				}
				timerService.init(globalContext);
				break;
			default:
				globalContext.bindService(service.getConsumerPort());
				break;
			}
		}
		closer.submit();
		// to obtain log when finished
		closer.closeAsyncUnattended(closer);
	}

}
