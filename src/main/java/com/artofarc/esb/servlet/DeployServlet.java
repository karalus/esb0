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

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.TimerService;
import com.artofarc.esb.action.HttpOutboundAction;
import com.artofarc.esb.artifact.Artifact;
import com.artofarc.esb.artifact.FileSystem.ChangeSet;
import com.artofarc.esb.artifact.ServiceArtifact;
import com.artofarc.esb.artifact.ValidationException;
import com.artofarc.esb.artifact.WorkerPoolArtifact;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.PoolContext;
import com.artofarc.esb.context.WorkerPool;
import com.artofarc.esb.jms.JMSConsumer;
import com.artofarc.esb.service.Protocol;

@WebServlet("/admin/deploy/*")
@MultipartConfig
public class DeployServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		PoolContext poolContext = (PoolContext) getServletContext().getAttribute(ESBServletContextListener.POOL_CONTEXT);
		GlobalContext globalContext = poolContext.getGlobalContext();
		Artifact artifact = globalContext.getFileSystem().getArtifact(req.getPathInfo());
		if (artifact != null) {
			String headerAccept = req.getHeader(HttpOutboundAction.HTTP_HEADER_ACCEPT);
			if (headerAccept.contains("text/")) {
				resp.setContentType("text/plain");
				resp.setHeader("Content-Disposition", "filename=\"" + artifact.getName() + "\"");
				resp.getOutputStream().write(artifact.getContent());
			} else {
//				Graph g = graph("example5").directed().with(node("abc").link(node("xyz")));
//				Graphviz viz = Graphviz.fromGraph(g);
			}
		} else {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND);
		}
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		PoolContext poolContext = (PoolContext) getServletContext().getAttribute(ESBServletContextListener.POOL_CONTEXT);
		GlobalContext globalContext = poolContext.getGlobalContext();
		if (req.getContentType().startsWith("multipart/")) {
			Part filePart = req.getPart("file"); // Retrieves <input type="file"
																// name="file">
			if (filePart != null) {
				log("Content Type is: " + filePart.getContentType());
				if (!filePart.getContentType().startsWith("application/")) {
					resp.sendError(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
					return;
				}
				ChangeSet updateSet;
				try {
					updateSet = globalContext.getFileSystem().createUpdate(globalContext, filePart.getInputStream());
				} catch (ValidationException e) {
					e.printStackTrace();
					resp.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
					return;
				}
				globalContext.setFileSystem(updateSet.getFileSystem());
				deployChangeSet(globalContext, poolContext, updateSet);
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
			}
		}
		resp.sendRedirect(req.getContextPath() + "/admin");
	}

	public static void deployChangeSet(GlobalContext globalContext, PoolContext poolContext, ChangeSet updateSet) {
		for (WorkerPoolArtifact workerPoolArtifact : updateSet.getWorkerPoolArtifacts()) {
			String name = WorkerPoolArtifact.stripExt(workerPoolArtifact.getURI());
			com.artofarc.esb.service.WorkerPool wpDef = workerPoolArtifact.getWorkerPool();
			WorkerPool workerPool = new WorkerPool(globalContext, name, wpDef.getMinThreads(), wpDef.getMaxThreads(), wpDef.getQueueDepth(), wpDef.getScheduledThreads());
			// TODO: close later
			globalContext.putWorkerPool(name, workerPool);
		}
		for (ServiceArtifact service : updateSet) {
			ConsumerPort consumerPort = service.getConsumerPort();
			if (service.getService().getProtocol() == Protocol.HTTP) {
				globalContext.bindHttpService(service.getService().getHttpBindURI(), consumerPort);
			} else if (service.getService().getProtocol() == Protocol.JMS) {
				JMSConsumer jmsConsumer = service.getConsumerPort();
				JMSConsumer oldConsumer = globalContext.bindJmsConsumer(jmsConsumer);
				try {
					if (oldConsumer != null) {
						oldConsumer.close();
					}
					jmsConsumer.init(poolContext);
				} catch (Exception e) {
					throw new RuntimeException("Could not create JMSConsumer: " + jmsConsumer.getKey(), e);
				}
			} else if (service.getService().getProtocol() == Protocol.TIMER) {
				TimerService timerService = service.getConsumerPort();
				globalContext.bindTimerService(timerService);
				timerService.init(globalContext);
			} else {
				globalContext.bindService(consumerPort);
			}
		}
	}

}