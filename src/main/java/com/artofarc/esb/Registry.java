/*
 * Copyright 2022 Andre Karalus
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
package com.artofarc.esb;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;

import com.artofarc.esb.context.AbstractContext;
import com.artofarc.esb.context.WorkerPool;
import com.artofarc.esb.http.HttpEndpointRegistry;
import com.artofarc.esb.http.HttpGlobalContext;
import com.artofarc.esb.jms.JMSConsumer;
import com.artofarc.esb.jms.JMSConsumerGroup;
import com.artofarc.esb.servlet.HttpConsumer;
import com.artofarc.util.PrefixBTree;

public class Registry extends AbstractContext {

	private static final int DEFAULT_NO_SERVICES = 512;
	protected static final String DEFAULT_WORKER_POOL = "default";

	private final Map<String, ConsumerPort> _services = new ConcurrentHashMap<>(DEFAULT_NO_SERVICES);
	private final Map<String, HttpConsumer> _httpServices = new ConcurrentHashMap<>(DEFAULT_NO_SERVICES >> 1);
	private final PrefixBTree<HttpConsumer> _mappedHttpServices = new PrefixBTree<>();
	private final Map<String, JMSConsumer> _jmsConsumer = new ConcurrentHashMap<>(DEFAULT_NO_SERVICES >> 1);
	private final Map<String, TimerService> _timerServices = new ConcurrentHashMap<>();
	private final Map<Path, FileWatchEventConsumer> _fileWatchEventServices = new ConcurrentHashMap<>();
	private final Map<String, KafkaConsumerPort> _kafkaConsumer = new ConcurrentHashMap<>();
	private final Map<String, WorkerPool> _workerPoolMap = new ConcurrentHashMap<>();
	private final HttpEndpointRegistry _httpEndpointRegistry = new HttpEndpointRegistry(this);
	private final HttpGlobalContext _httpGlobalContext = new HttpGlobalContext();
	private final MBeanServer _mbs;
	private final String OBJECT_NAME = "com.artofarc.esb:type=" + getClass().getSimpleName();
	private final Map<String, ObjectName> _registered = new ConcurrentHashMap<>(DEFAULT_NO_SERVICES);

	public Registry(MBeanServer mbs) {
		_mbs = mbs;
		registerMBean(this, "");
	}

	public final MBeanServer getMBeanServer() {
		return _mbs;
	}

	public final HttpEndpointRegistry getHttpEndpointRegistry() {
		return _httpEndpointRegistry;
	}

	public final HttpGlobalContext getHttpGlobalContext() {
		return _httpGlobalContext;
	}

	public final MBeanServer getPlatformMBeanServer() {
		return _mbs;
	}

	public final void registerMBean(Object object, String postfix) {
		if (_mbs != null) {
			try {
				String name = OBJECT_NAME + postfix;
				ObjectInstance objectInstance = _mbs.registerMBean(object, new ObjectName(name));
				_registered.put(name, objectInstance.getObjectName());
			} catch (JMException e) {
				throw new RuntimeException(e);
			}
		}
	}

	public final void unregisterMBean(String postfix) {
		if (_mbs != null) {
			try {
				ObjectName name = _registered.remove(OBJECT_NAME + postfix);
				if (name != null) {
					_mbs.unregisterMBean(name);
				}
			} catch (JMException e) {
				logger.error("unregisterMBean failed", e);
			}
		}
	}

	public final ConsumerPort getInternalService(String uri) {
		return _services.get(uri);
	}

	public final List<ConsumerPort> getInternalServices() {
		List<ConsumerPort> result = new ArrayList<>();
		for (ConsumerPort consumerPort : _services.values()) {
			if (consumerPort.getClass() == ConsumerPort.class) {
				result.add(consumerPort);
			}
		}
		result.sort(Comparator.comparing(ConsumerPort::getUri));
		return result;
	}

	public final Set<String> getHttpServicePaths() {
		return _httpServices.keySet();
	}

	public final List<String> getMappedHttpServicePaths() {
		return _mappedHttpServices.getKeys();
	}

	public final List<HttpConsumer> getHttpConsumers() {
		List<HttpConsumer> result = _mappedHttpServices.getValues();
		result.addAll(_httpServices.values());
		return result;
	}

	public final Collection<JMSConsumer> getJMSConsumers() {
		return _jmsConsumer.values();
	}

	public final Collection<TimerService> getTimerServices() {
		return _timerServices.values();
	}

	public final Collection<FileWatchEventConsumer> getFileWatchEventConsumers() {
		return _fileWatchEventServices.values();
	}

	public final Collection<KafkaConsumerPort> getKafkaConsumers() {
		return _kafkaConsumer.values();
	}

	public final HttpConsumer getHttpService(String path) {
		HttpConsumer httpService = _httpServices.get(path);
		if (httpService == null) {
			return _mappedHttpServices.search(path);
		}
		return httpService;
	}

	public final ConsumerPort bindInternalService(ConsumerPort consumerPort) {
		ConsumerPort oldConsumerPort = _services.put(consumerPort.getUri(), consumerPort);
		if (oldConsumerPort != null) {
			oldConsumerPort.unbind(this);
		}
		return oldConsumerPort;
	}

	private void registerInternalService(ConsumerPort consumerPort) {
		_services.put(consumerPort.getUri(), consumerPort);
	}

	public final void unregisterInternalService(ConsumerPort consumerPort) {
		_services.remove(consumerPort.getUri());
	}

	private void unregisterInternalServiceAndMBean(ConsumerPort consumerPort) {
		_services.remove(consumerPort.getUri());
		unregisterMBean(consumerPort.getMBeanPostfix());
	}

	public final HttpConsumer checkBindHttpService(HttpConsumer httpConsumer) {
		HttpConsumer oldConsumerPort = httpConsumer.isPathMapping() ? _mappedHttpServices.get(httpConsumer.getBindPath()) : _httpServices.get(httpConsumer.getBindPath());
		if (oldConsumerPort != null && !oldConsumerPort.getUri().equals(httpConsumer.getUri())) {
			throw new IllegalArgumentException("A different service is already bound to this path: " + oldConsumerPort.getUri());
		}
		return oldConsumerPort;
	}

	public final ConsumerPort bindHttpService(HttpConsumer httpConsumer) {
		ConsumerPort oldConsumerPort = checkBindHttpService(httpConsumer);
		if (oldConsumerPort == null) {
			oldConsumerPort = getInternalService(httpConsumer.getUri());
		}
		if (oldConsumerPort != null) {
			oldConsumerPort.unbind(this);
		}
		httpConsumer.bind(this);
		registerInternalService(httpConsumer);
		return oldConsumerPort;
	}

	public final void registerHttpService(HttpConsumer httpConsumer) {
		if (httpConsumer.isPathMapping()) {
			_mappedHttpServices.upsert(httpConsumer.getBindPath(), httpConsumer);
		} else {
			_httpServices.put(httpConsumer.getBindPath(), httpConsumer);
		}
	}

	public final void unbindHttpService(HttpConsumer httpConsumer) {
		unregisterInternalServiceAndMBean(httpConsumer.isPathMapping() ? _mappedHttpServices.remove(httpConsumer.getBindPath()) : _httpServices.remove(httpConsumer.getBindPath()));
	}

	public final JMSConsumer checkBindJmsConsumer(JMSConsumer jmsConsumer) {
		JMSConsumer oldConsumerPort = _jmsConsumer.get(jmsConsumer.getKey());
		if (oldConsumerPort != null && !oldConsumerPort.getUri().equals(jmsConsumer.getUri())) {
			throw new IllegalArgumentException("A different service is already bound: " + oldConsumerPort.getUri());
		}
		return oldConsumerPort;
	}

	public final ConsumerPort bindJmsConsumer(JMSConsumer jmsConsumer) {
		ConsumerPort oldConsumerPort = checkBindJmsConsumer(jmsConsumer);
		if (oldConsumerPort == null) {
			oldConsumerPort = getInternalService(jmsConsumer.getUri());
		}
		if (oldConsumerPort != null) {
			oldConsumerPort.unbind(this);
		}
		jmsConsumer.bind(this);
		registerInternalService(jmsConsumer);
		return oldConsumerPort;
	}

	public final ConsumerPort bindJmsConsumer(JMSConsumerGroup jmsConsumerGroup) {
		ConsumerPort oldConsumerPort = null;
		for (JMSConsumer jmsConsumer : jmsConsumerGroup.getGroup()) {
			oldConsumerPort = checkBindJmsConsumer(jmsConsumer);
			if (oldConsumerPort instanceof JMSConsumer) {
				// get old JMSConsumerGroup if there is one
				oldConsumerPort = getInternalService(jmsConsumer.getUri());
			}
		}
		if (oldConsumerPort == null) {
			oldConsumerPort = getInternalService(jmsConsumerGroup.getUri());
		}
		if (oldConsumerPort != null) {
			oldConsumerPort.unbind(this);
		}
		jmsConsumerGroup.bind(this);
		registerInternalService(jmsConsumerGroup);
		return oldConsumerPort;
	}

	public final void registerJmsConsumer(JMSConsumer jmsConsumer) {
		_jmsConsumer.put(jmsConsumer.getKey(), jmsConsumer);
	}

	public final void unbindJmsConsumer(JMSConsumer jmsConsumer) {
		unregisterInternalServiceAndMBean(_jmsConsumer.remove(jmsConsumer.getKey()));
	}

	public final ConsumerPort bindTimerService(TimerService timerService) {
		ConsumerPort oldConsumerPort = _timerServices.put(timerService.getUri(), timerService);
		if (oldConsumerPort == null) {
			oldConsumerPort = getInternalService(timerService.getUri());
		}
		if (oldConsumerPort != null) {
			oldConsumerPort.unbind(this);
		}
		timerService.bind(this);
		registerInternalService(timerService);
		return oldConsumerPort;
	}

	public final void unbindTimerService(TimerService timerService) {
		unregisterInternalServiceAndMBean(_timerServices.remove(timerService.getUri()));
	}

	public final ConsumerPort checkBindFileWatchEventService(FileWatchEventConsumer fileWatchEventConsumer) {
		ConsumerPort oldConsumerPort = null;
		for (Path dir : fileWatchEventConsumer.getDirs()) {
			if (_fileWatchEventServices.containsKey(dir)) {
				oldConsumerPort = _fileWatchEventServices.get(dir);
				if (!oldConsumerPort.getUri().equals(fileWatchEventConsumer.getUri())) {
					throw new IllegalArgumentException("A different service is already bound: " + oldConsumerPort.getUri());
				}
			}
		}
		return oldConsumerPort;
	}

	public final ConsumerPort bindFileWatchEventService(FileWatchEventConsumer fileWatchEventConsumer) {
		ConsumerPort oldConsumerPort = checkBindFileWatchEventService(fileWatchEventConsumer);
		if (oldConsumerPort == null) {
			oldConsumerPort = getInternalService(fileWatchEventConsumer.getUri());
		}
		if (oldConsumerPort != null) {
			oldConsumerPort.unbind(this);
		}
		fileWatchEventConsumer.bind(this);
		registerInternalService(fileWatchEventConsumer);
		return oldConsumerPort;
	}

	public final void registerFileWatchEventService(FileWatchEventConsumer fileWatchEventConsumer) {
		for (Path dir : fileWatchEventConsumer.getDirs()) {
			_fileWatchEventServices.put(dir, fileWatchEventConsumer);
		}
	}

	public final void unbindFileWatchEventService(FileWatchEventConsumer fileWatchEventConsumer) {
		ConsumerPort oldConsumerPort = null;
		for (Path dir : fileWatchEventConsumer.getDirs()) {
			oldConsumerPort = _fileWatchEventServices.remove(dir);
		}
		unregisterInternalServiceAndMBean(oldConsumerPort);
	}

	public final ConsumerPort checkBindKafkaConsumer(KafkaConsumerPort kafkaConsumer) {
		ConsumerPort oldConsumerPort = _kafkaConsumer.get(kafkaConsumer.getKey());
		if (oldConsumerPort != null && !oldConsumerPort.getUri().equals(kafkaConsumer.getUri())) {
			throw new IllegalArgumentException("A different service is already bound: " + oldConsumerPort.getUri());
		}
		return oldConsumerPort;
	}

	public final ConsumerPort bindKafkaConsumer(KafkaConsumerPort kafkaConsumer) {
		ConsumerPort oldConsumerPort = checkBindKafkaConsumer(kafkaConsumer);
		if (oldConsumerPort == null) {
			oldConsumerPort = getInternalService(kafkaConsumer.getUri());
		}
		if (oldConsumerPort != null) {
			oldConsumerPort.unbind(this);
		}
		kafkaConsumer.bind(this);
		registerInternalService(kafkaConsumer);
		return oldConsumerPort;
	}

	public final void registerKafkaConsumer(KafkaConsumerPort kafkaConsumer) {
		_kafkaConsumer.put(kafkaConsumer.getKey(), kafkaConsumer);
	}

	public final void unbindKafkaConsumer(KafkaConsumerPort kafkaConsumer) {
		unregisterInternalServiceAndMBean(_kafkaConsumer.remove(kafkaConsumer.getKey()));
	}

	public final Collection<WorkerPool> getWorkerPools() {
		return _workerPoolMap.values();
	}

	public final WorkerPool getWorkerPool(String name) {
		return _workerPoolMap.get(name != null ? name : DEFAULT_WORKER_POOL);
	}

	public final WorkerPool getDefaultWorkerPool() {
		return _workerPoolMap.get(DEFAULT_WORKER_POOL);
	}

	public final void putWorkerPool(String name, WorkerPool workerPool) {
		WorkerPool old = _workerPoolMap.put(name, workerPool);
		if (old != null) {
			unregisterMBean(",group=WorkerPool,name=" + name);
		}
		registerMBean(workerPool, ",group=WorkerPool,name=" + name);
	}

	public final void removeWorkerPool(String name) {
		_workerPoolMap.remove(name);
		unregisterMBean(",group=WorkerPool,name=" + name);
	}

	@Override
	public void close() {
		super.close();
		try {
			for (ObjectName objectName : _registered.values()) {
				_mbs.unregisterMBean(objectName);
			}
		} catch (JMException e) {
			logger.warn("unregisterMBean failed", e);
		}
	}

}
