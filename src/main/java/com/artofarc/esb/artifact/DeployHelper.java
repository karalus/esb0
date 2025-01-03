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
package com.artofarc.esb.artifact;

import java.util.Collection;
import java.util.HashSet;

import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.FileWatchEventConsumer;
import com.artofarc.esb.KafkaConsumerPort;
import com.artofarc.esb.TimerService;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.WorkerPool;
import com.artofarc.esb.jms.JMSConsumer;
import com.artofarc.esb.jms.JMSConsumerGroup;
import com.artofarc.esb.resource.LRUCacheWithExpirationFactory;
import com.artofarc.esb.servlet.HttpConsumer;
import com.artofarc.util.Closer;
import com.artofarc.util.DataStructures;
import com.artofarc.util.IOUtils;

public final class DeployHelper {

	public static void attachFileSystemAndDeploy(GlobalContext globalContext, String root) throws Exception {
		FileSystem fileSystem;
		if (root.contains("jdbc")) {
			fileSystem = new FileSystemDB(globalContext.<javax.sql.DataSource> lookup(root));
		} else {
			fileSystem = new FileSystemDir(root);
		}
		globalContext.setFileSystem(fileSystem);
		XMLCatalog.attachToFileSystem(globalContext);
		deployChangeSet(globalContext, fileSystem.init(globalContext));
		// necessary for auto migrated artifacts
		fileSystem.writeBackChanges();
	}

	public static void deployChangeSet(GlobalContext globalContext, FileSystem.ChangeSet changeSet) throws ValidationException {
		Collection<ServiceArtifact> serviceArtifacts = changeSet.getServiceArtifacts();
		Closer closer = new Closer(globalContext.getDefaultWorkerPool().getExecutorService());
		DataStructures.typeSelect(changeSet.getDeletedArtifacts(), ServiceArtifact.class).forEach(service -> {
			switch (service.getProtocol()) {
			case HTTP:
				HttpConsumer httpConsumer = service.getConsumerPort();
				globalContext.unbindHttpService(httpConsumer);
				closer.closeAsync(httpConsumer);
				break;
			case JMS:
				ConsumerPort consumer = service.getConsumerPort();
				if (consumer instanceof JMSConsumer) {
					JMSConsumer jmsConsumer = (JMSConsumer) consumer;
					globalContext.unbindJmsConsumer(jmsConsumer);
					jmsConsumer.unsubscribe();
					jmsConsumer.close();
				} else {
					for (JMSConsumer jmsConsumer : ((JMSConsumerGroup) consumer).getGroup()) {
						globalContext.unbindJmsConsumer(jmsConsumer);
						jmsConsumer.unsubscribe();
						jmsConsumer.close();
					}
				}
				break;
			case FILE:
				FileWatchEventConsumer fileWatchEventConsumer = service.getConsumerPort();
				globalContext.unbindFileWatchEventService(fileWatchEventConsumer);
				fileWatchEventConsumer.close();
				break;
			case KAFKA:
				KafkaConsumerPort kafkaConsumerPort = service.getConsumerPort();
				globalContext.unbindKafkaConsumer(kafkaConsumerPort);
				kafkaConsumerPort.close();
				break;
			case TIMER:
				TimerService timerService = service.getConsumerPort();
				globalContext.unbindTimerService(timerService);
				closer.closeAsync(timerService);
				break;
			default:
				globalContext.unregisterInternalService(service.getConsumerPort());
				break;
			}
		});
		DataStructures.typeSelect(changeSet.getDeletedArtifacts(), WorkerPoolArtifact.class).forEach(workerPool -> {
			String name = IOUtils.stripExt(workerPool.getURI());
			WorkerPool oldWorkerPool = globalContext.getWorkerPool(name);
			// close later
			closer.add(() -> {
				oldWorkerPool.close();
				oldWorkerPool.getPoolContext().close();
				globalContext.removeWorkerPool(name);
			});
		});
		DataStructures.typeSelect(changeSet.getDeletedArtifacts(), JNDIObjectFactoryArtifact.class).forEach(jndiObjectFactoryArtifact -> {
			closer.add(() -> {
				Object object = globalContext.removeJndiObject(jndiObjectFactoryArtifact.getJndiName());
				if (object instanceof AutoCloseable) {
					((AutoCloseable) object).close();
				}
			});
		});
		HashSet<String> usedCaches = new HashSet<>();
		for (WorkerPoolArtifact workerPoolArtifact : changeSet.getWorkerPoolArtifacts()) {
			String name = IOUtils.stripExt(workerPoolArtifact.getURI());
			com.artofarc.esb.service.WorkerPool wpDef = workerPoolArtifact.getWorkerPool();
			WorkerPool oldWorkerPool = globalContext.getWorkerPool(name);
			if (oldWorkerPool != null) {
				if (!oldWorkerPool.tryUpdate(wpDef.getMinThreads(), wpDef.getMaxThreads(),
						wpDef.getPriority(), wpDef.getQueueDepth(), wpDef.getScheduledThreads(), wpDef.isAllowCoreThreadTimeOut(), wpDef.isRetry())) {

					globalContext.putWorkerPool(name, new WorkerPool(oldWorkerPool.getPoolContext(), name, wpDef.getMinThreads(), wpDef.getMaxThreads(),
							wpDef.getPriority(), wpDef.getQueueDepth(), wpDef.getScheduledThreads(), wpDef.isAllowCoreThreadTimeOut(), wpDef.isRetry()));
					// close later
					closer.add(oldWorkerPool);
				}
			} else {
				globalContext.putWorkerPool(name, new WorkerPool(globalContext, name, wpDef.getMinThreads(), wpDef.getMaxThreads(),
						wpDef.getPriority(), wpDef.getQueueDepth(), wpDef.getScheduledThreads(), wpDef.isAllowCoreThreadTimeOut(), wpDef.isRetry()));
			}
		}
		for (JNDIObjectFactoryArtifact jndiObjectFactoryArtifact : changeSet.getJNDIObjectFactoryArtifacts()) {
			try {
				Object oldObject = globalContext.getProperty(jndiObjectFactoryArtifact.getJndiName());
				if (jndiObjectFactoryArtifact.tryUpdate(oldObject)) {
					continue;
				}
				if (oldObject instanceof AutoCloseable) {
					closer.add((AutoCloseable) oldObject);
				}
			} catch (javax.naming.NamingException e) {
				// no oldObject
			}
			globalContext.putJndiObject(jndiObjectFactoryArtifact.getJndiName(), jndiObjectFactoryArtifact.createObject(), jndiObjectFactoryArtifact.getURI());
		}
		for (ServiceArtifact service : serviceArtifacts) {
			ConsumerPort oldConsumerPort;
			switch (service.getProtocol()) {
			case HTTP:
				HttpConsumer httpConsumer = service.getConsumerPort();
				httpConsumer.init(globalContext);
				oldConsumerPort = globalContext.bindHttpService(httpConsumer);
				if (oldConsumerPort != null) {
					closer.closeAsync(oldConsumerPort);
				}
				break;
			case JMS:
				ConsumerPort consumer = service.getConsumerPort();
				if (consumer instanceof JMSConsumer) {
					JMSConsumer jmsConsumer = (JMSConsumer) consumer;
					oldConsumerPort = globalContext.bindJmsConsumer(jmsConsumer);
					if (oldConsumerPort != null) {
						if (consumer.needsSyncClose(oldConsumerPort)) {
							Closer.closeQuietly(oldConsumerPort);
						} else {
							closer.closeAsync(oldConsumerPort);
						}
					}
					try {
						jmsConsumer.init(globalContext);
					} catch (Exception e) {
						Artifact.logger.info("Could not init JMSConsumer " + jmsConsumer.getKey(), e);
						// ignore, if JMS is down we reconnect later
					}
				} else {
					JMSConsumerGroup jmsConsumerGroup = (JMSConsumerGroup) consumer;
					oldConsumerPort = globalContext.bindJmsConsumer(jmsConsumerGroup);
					if (oldConsumerPort != null) {
						if (consumer.needsSyncClose(oldConsumerPort)) {
							Closer.closeQuietly(oldConsumerPort);
						} else {
							closer.closeAsync(oldConsumerPort);
						}
					}
					for (JMSConsumer jmsConsumer : jmsConsumerGroup.getGroup()) {
						try {
							jmsConsumer.init(globalContext);
						} catch (Exception e) {
							Artifact.logger.info("Could not init JMSConsumer " + jmsConsumer.getKey(), e);
							// ignore, if JMS is down we reconnect later
						}
					}
				}
				break;
			case FILE:
				FileWatchEventConsumer fileWatchEventConsumer = service.getConsumerPort();
				oldConsumerPort = globalContext.bindFileWatchEventService(fileWatchEventConsumer);
				if (oldConsumerPort != null) {
					closer.closeAsync(oldConsumerPort);
				}
				fileWatchEventConsumer.init(globalContext);
				break;
			case KAFKA:
				KafkaConsumerPort kafkaConsumerPort = service.getConsumerPort();
				oldConsumerPort = globalContext.bindKafkaConsumer(kafkaConsumerPort);
				if (oldConsumerPort != null) {
					closer.closeAsync(oldConsumerPort);
				}
				kafkaConsumerPort.init(globalContext);
				break;
			case TIMER:
				TimerService timerService = service.getConsumerPort();
				oldConsumerPort = globalContext.bindTimerService(timerService);
				if (oldConsumerPort != null) {
					closer.closeAsync(oldConsumerPort);
				}
				timerService.init(globalContext);
				break;
			default:
				oldConsumerPort = globalContext.bindInternalService(service.getConsumerPort());
				if (oldConsumerPort != null) {
					Closer.closeQuietly(oldConsumerPort);
				}
				break;
			}
			if (oldConsumerPort != null) {
				usedCaches.addAll(oldConsumerPort.getUsedCaches());
			}
		}
		closer.submit();
		// to obtain log when finished
		closer.closeAsyncUnattended(closer);
		// invalidate used caches
		@SuppressWarnings("unchecked")
		LRUCacheWithExpirationFactory<Object, Object[]> factory = globalContext.getResourceFactory(LRUCacheWithExpirationFactory.class);
		for (String cacheName : usedCaches) {
			LRUCacheWithExpirationFactory<Object, Object[]>.Cache cache = factory.peekResource(cacheName);
			if (cache != null) {
				cache.clear();
			}
		}
	}

	public static void createAdminService(GlobalContext globalContext, String path) throws Exception {
		HttpConsumer adminService = globalContext.getHttpService(path);
		if (adminService == null || !adminService.getBindPath().startsWith(path)) {
			Directory parent = globalContext.getFileSystem().makeDirectory("admin");
			ServiceArtifact serviceArtifact = new ServiceArtifact(globalContext.getFileSystem(), parent, "Admin.xservice");
			serviceArtifact.setContent(IOUtils.toByteArray(globalContext.getResourceAsStream("Admin.xservice")));
			serviceArtifact.validate(globalContext);
			adminService = serviceArtifact.getConsumerPort();
			adminService.init(globalContext);
			globalContext.bindHttpService(adminService);
		}
	}

	public static void main(String[] args) throws Exception {
		try (GlobalContext globalContext = new GlobalContext(java.lang.management.ManagementFactory.getPlatformMBeanServer())) {
			attachFileSystemAndDeploy(globalContext, args[0]);
		}
	}

}
