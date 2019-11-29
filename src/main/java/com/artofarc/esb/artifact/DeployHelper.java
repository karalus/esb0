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
package com.artofarc.esb.artifact;

import java.util.List;

import com.artofarc.esb.TimerService;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.WorkerPool;
import com.artofarc.esb.jms.JMSConsumer;
import com.artofarc.esb.servlet.HttpConsumer;
import com.artofarc.util.Closer;

public final class DeployHelper {

	public static int deployChangeSet(GlobalContext globalContext, FileSystem.ChangeSet changeSet) throws ValidationException {
		List<ServiceArtifact> serviceArtifacts = changeSet.getServiceArtifacts();
		Closer closer = new Closer(globalContext.getDefaultWorkerPool().getExecutorService());
		for (ServiceArtifact service : changeSet.getDeletedServiceArtifacts()) {
			switch (service.getProtocol()) {
			case HTTP:
				HttpConsumer httpConsumer = service.getConsumerPort();
				globalContext.unbindHttpService(httpConsumer.getBindPath());
				closer.closeAsync(httpConsumer);
				break;
			case JMS:
				JMSConsumer jmsConsumer = service.getConsumerPort();
				globalContext.unbindJmsConsumer(jmsConsumer);
				try {
					jmsConsumer.close();
				} catch (Exception e) {
					// ignore
				}
				break;
			case TIMER:
				TimerService timerService = service.getConsumerPort();
				globalContext.unbindTimerService(timerService);
				closer.closeAsync(timerService);
				break;
			default:
				globalContext.unbindService(service.getConsumerPort());
				break;
			}
		}
		for (WorkerPoolArtifact workerPoolArtifact : changeSet.getWorkerPoolArtifacts()) {
			String name = Artifact.stripExt(workerPoolArtifact.getURI());
			com.artofarc.esb.service.WorkerPool wpDef = workerPoolArtifact.getWorkerPool();
			WorkerPool oldWorkerPool = globalContext.getWorkerPool(name);
			if (oldWorkerPool != null) {
				globalContext.putWorkerPool(name, new WorkerPool(oldWorkerPool.getPoolContext(), name, wpDef.getMinThreads(), wpDef.getMaxThreads(),
						wpDef.getPriority(), wpDef.getQueueDepth(), wpDef.getScheduledThreads(), wpDef.isAllowCoreThreadTimeOut()));
				// close later
				closer.add(oldWorkerPool);
			} else {
				globalContext.putWorkerPool(name, new WorkerPool(globalContext, name, wpDef.getMinThreads(), wpDef.getMaxThreads(),
						wpDef.getPriority(), wpDef.getQueueDepth(), wpDef.getScheduledThreads(), wpDef.isAllowCoreThreadTimeOut()));
			}
		}
		for (ServiceArtifact service : serviceArtifacts) {
			switch (service.getProtocol()) {
			case HTTP:
				HttpConsumer httpConsumer = service.getConsumerPort();
				httpConsumer.init(globalContext);
				HttpConsumer oldHttpConsumer = globalContext.bindHttpService(httpConsumer.getBindPath(), httpConsumer);
				if (oldHttpConsumer != null) {
					closer.closeAsync(oldHttpConsumer);
				}
				break;
			case JMS:
				JMSConsumer jmsConsumer = service.getConsumerPort();
				JMSConsumer oldConsumer = globalContext.bindJmsConsumer(jmsConsumer);
				if (oldConsumer != null) {
					try {
						oldConsumer.close();
					} catch (Exception e) {
						// ignore
					}
				}
				try {
					jmsConsumer.init(globalContext);
				} catch (Exception e) {
					Artifact.logger.info("Could not init JMSConsumer " + jmsConsumer.getKey(), e);
					// ignore, if JMS is down we reconnect later
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
		return serviceArtifacts.size();
	}

	public static void createAdminService(GlobalContext globalContext, String path) throws ValidationException {
		HttpConsumer adminService = globalContext.getHttpService(path);
		if (adminService == null) {
			Directory parent = globalContext.getFileSystem().makeDirectory("admin");
			ServiceArtifact serviceArtifact = new ServiceArtifact(globalContext.getFileSystem(), parent, "Admin.xservice");
			serviceArtifact.setContent("<service protocol=\"http\" version=\"1\" xmlns=\"http://www.artofarc.com/esb/service\">\n\t<httpBindURI maxPool=\"2\">/admin/deploy*</httpBindURI>\n\t<admin/>\n</service>".getBytes());
			serviceArtifact.validate(globalContext);
			adminService = serviceArtifact.getConsumerPort();
			adminService.init(globalContext);
			globalContext.bindHttpService(adminService.getBindPath(), adminService);
		}
	}

}
