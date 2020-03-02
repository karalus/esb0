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
package com.artofarc.esb;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;

import com.artofarc.esb.context.AbstractContext;
import com.artofarc.esb.jms.JMSConsumer;
import com.artofarc.esb.servlet.HttpConsumer;
import com.artofarc.util.PrefixBTree;
import com.artofarc.util.Closer;

public class Registry extends AbstractContext {

	private static final int DEFAULT_NO_SERVICES = 256;

	private final ConcurrentHashMap<String, ConsumerPort> _services = new ConcurrentHashMap<>(DEFAULT_NO_SERVICES);
	private final ConcurrentHashMap<String, HttpConsumer> _httpServices = new ConcurrentHashMap<>(DEFAULT_NO_SERVICES >> 1);
	private final PrefixBTree<HttpConsumer> _mappedHttpServices = new PrefixBTree<>();
	private final ConcurrentHashMap<String, JMSConsumer> _jmsConsumer = new ConcurrentHashMap<>(DEFAULT_NO_SERVICES >> 1);
	private final ConcurrentHashMap<String, TimerService> _timerServices = new ConcurrentHashMap<>();

	private final MBeanServer _mbs;
	private final String OBJECT_NAME = "com.artofarc.esb:type=" + getClass().getSimpleName();
	private final ConcurrentHashMap<String, ObjectName> _registered = new ConcurrentHashMap<>(DEFAULT_NO_SERVICES);

	public Registry(MBeanServer mbs) {
		_mbs = mbs;
		registerMBean(this, "");
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
				_mbs.unregisterMBean(_registered.remove(OBJECT_NAME + postfix));
			} catch (JMException e) {
				logger.error("unregisterMBean failed", e);
			}
		}
	}

	public final ConsumerPort getInternalService(String uri) {
		return _services.get(uri);
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

	public final HttpConsumer getHttpService(String path) {
		HttpConsumer httpService = _httpServices.get(path);
		if (httpService == null) {
			return _mappedHttpServices.search(path);
		}
		return httpService;
	}

	public final ConsumerPort bindInternalService(ConsumerPort consumerPort) {
		ConsumerPort oldConsumerPort = bindService(consumerPort); 
		unbindService(oldConsumerPort);
		return oldConsumerPort;
	}

	private ConsumerPort bindService(ConsumerPort consumerPort) {
		ConsumerPort oldConsumerPort = _services.put(consumerPort.getUri(), consumerPort);
		String postfix = consumerPort.getMBeanPostfix();
		if (oldConsumerPort != null) {
			unregisterMBean(postfix);
		}
		registerMBean(consumerPort, postfix);
		return oldConsumerPort;
	}

	public final void unbindInternalService(ConsumerPort consumerPort) {
		unregisterMBean(_services.remove(consumerPort.getUri()).getMBeanPostfix());
	}

	private void unbindService(ConsumerPort consumerPort) {
		if (consumerPort instanceof HttpConsumer) {
			HttpConsumer httpService = (HttpConsumer) consumerPort;
			if (httpService.isPathMapping()) {
				_mappedHttpServices.remove(httpService.getBindPath());
			} else {
				_httpServices.remove(httpService.getBindPath());
			}
		} else if (consumerPort instanceof JMSConsumer) {
			JMSConsumer jmsConsumer = (JMSConsumer) consumerPort;
			_jmsConsumer.remove(jmsConsumer.getKey());
		} else if (consumerPort instanceof TimerService) {
			TimerService timerService = (TimerService) consumerPort;
			_timerServices.remove(timerService.getUri());
		}
	}

	public final ConsumerPort bindHttpService(HttpConsumer httpConsumer) {
		ConsumerPort oldConsumerPort = httpConsumer.isPathMapping() ? _mappedHttpServices.get(httpConsumer.getBindPath()) : _httpServices.get(httpConsumer.getBindPath());
		if (oldConsumerPort != null) {
			if (!oldConsumerPort.getUri().equals(httpConsumer.getUri())) {
				throw new IllegalArgumentException("A different service is already bound to this path: " + oldConsumerPort.getUri());
			}
		} else {
			oldConsumerPort = getInternalService(httpConsumer.getUri());
			unbindService(oldConsumerPort);
		}
		bindService(httpConsumer);
		if (httpConsumer.isPathMapping()) {
			_mappedHttpServices.insert(httpConsumer.getBindPath(), httpConsumer);
		} else {
			_httpServices.put(httpConsumer.getBindPath(), httpConsumer);
		}
		return oldConsumerPort;
	}

	public final void unbindHttpService(HttpConsumer httpConsumer) {
		unbindInternalService(httpConsumer.isPathMapping() ? _mappedHttpServices.remove(httpConsumer.getBindPath()) : _httpServices.remove(httpConsumer.getBindPath()));
	}

	public final ConsumerPort bindJmsConsumer(JMSConsumer jmsConsumer) {
		ConsumerPort oldConsumerPort = _jmsConsumer.get(jmsConsumer.getKey());
		if (oldConsumerPort != null) {
			if (!oldConsumerPort.getUri().equals(jmsConsumer.getUri())) {
				throw new IllegalArgumentException("A different service is already bound: " + oldConsumerPort.getUri());
			}
		} else {
			oldConsumerPort = getInternalService(jmsConsumer.getUri());
			unbindService(oldConsumerPort);
		}
		bindService(jmsConsumer);
		_jmsConsumer.put(jmsConsumer.getKey(), jmsConsumer);
		return oldConsumerPort;
	}

	public final void unbindJmsConsumer(JMSConsumer jmsConsumer) {
		unbindInternalService(_jmsConsumer.remove(jmsConsumer.getKey()));
	}

	public final ConsumerPort bindTimerService(TimerService timerService) {
		ConsumerPort oldConsumerPort = _timerServices.put(timerService.getUri(), timerService);
		if (oldConsumerPort != null) {
			bindService(timerService);
		} else {
			oldConsumerPort = bindService(timerService);
			unbindService(oldConsumerPort);
		}
		return oldConsumerPort;
	}

	public final void unbindTimerService(TimerService timerService) {
		unbindInternalService(_timerServices.remove(timerService.getUri()));
	}

	public final void stopIngress() {
		for (TimerService timerService : _timerServices.values()) {
			timerService.close();
		}
		for (HttpConsumer httpConsumer : getHttpConsumers()) {
			try {
				httpConsumer.enable(false);
			} catch (Exception e) {
				// ignore
			}
		}
		for (JMSConsumer jmsConsumer : _jmsConsumer.values()) {
			Closer.closeQuietly(jmsConsumer);
		}
	}

	@Override
	public synchronized void close() {
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
