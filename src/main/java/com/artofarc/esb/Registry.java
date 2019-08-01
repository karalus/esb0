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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import com.artofarc.esb.context.AbstractContext;
import com.artofarc.esb.jms.JMSConsumer;
import com.artofarc.esb.servlet.HttpConsumer;
import com.artofarc.util.Closer;

public class Registry extends AbstractContext {

	private static final int DEFAULT_NO_SERVICES = 64;
	private static final int CONCURRENT_UPDATES = 4;

	private final ConcurrentHashMap<String, ConsumerPort> _services = new ConcurrentHashMap<>(DEFAULT_NO_SERVICES, 0.75f, CONCURRENT_UPDATES);
	private final ConcurrentHashMap<String, HttpConsumer> _httpServices = new ConcurrentHashMap<>(DEFAULT_NO_SERVICES, 0.75f, CONCURRENT_UPDATES);
	private final ConcurrentHashMap<String, JMSConsumer> _jmsConsumer = new ConcurrentHashMap<>(DEFAULT_NO_SERVICES, 0.75f, CONCURRENT_UPDATES);
	private final ConcurrentHashMap<String, TimerService> _timerServices = new ConcurrentHashMap<>(DEFAULT_NO_SERVICES, 0.75f, CONCURRENT_UPDATES);

	private final MBeanServer _mbs;
	private final String OBJECT_NAME = "com.artofarc.esb:type=" + getClass().getSimpleName();
	
	public Registry(MBeanServer mbs) {
		_mbs = mbs;
		if (_mbs != null) {
			try {
				_mbs.registerMBean(this, new ObjectName(OBJECT_NAME));
			} catch (JMException e) {
				throw new RuntimeException(e);
			}
		}
	}
	
	public void registerMBean(Object object, String postfix) {
		if (_mbs != null) {
			try {
				_mbs.registerMBean(object, new ObjectName(OBJECT_NAME + postfix));
			} catch (JMException e) {
				throw new RuntimeException(e);
			}
		}
	}

	public void unregisterMBean(String postfix) {
		if (_mbs != null) {
			try {
				_mbs.unregisterMBean(new ObjectName(OBJECT_NAME + postfix));
			} catch (JMException e) {
				// ignore
			}
		}
	}

	public void unregisterMBean(ObjectName name) {
		try {
			_mbs.unregisterMBean(name);
		} catch (JMException e) {
			// ignore
		}
	}

	public ConsumerPort getInternalService(String uri) {
		return _services.get(uri);
	}

	public Set<String> getHttpServicePaths() {
		return _httpServices.keySet();
	}

	public Collection<JMSConsumer> getJMSConsumers() {
		return _jmsConsumer.values();
	}

	public Collection<TimerService> getTimerServices() {
		return _timerServices.values();
	}

	public HttpConsumer getHttpService(String path) {
		HttpConsumer httpService = _httpServices.get(path);
		if (httpService == null) {
			// TOREVIEW: Find service for a shorter path
			for (String url : _httpServices.keySet()) {
				if (url.endsWith("*")) {
					if (path.startsWith(url.substring(0, url.length() - 1))) {
						return _httpServices.get(url);
					}
				}
			}
		}
		return httpService;
	}

	public void bindService(ConsumerPort consumerPort) {
		ConsumerPort old = _services.put(consumerPort.getUri(), consumerPort);
		String postfix = consumerPort.getMBeanPostfix();
		if (old != null) {
			unregisterMBean(postfix);
		}
		registerMBean(consumerPort, postfix);
	}

	public void unbindService(ConsumerPort consumerPort) {
		unregisterMBean(_services.remove(consumerPort.getUri()).getMBeanPostfix());
	}

	public HttpConsumer bindHttpService(String path, HttpConsumer consumerPort) {
		HttpConsumer httpService = _httpServices.get(path);
		if (httpService != null && !httpService.getUri().equals(consumerPort.getUri())) {
			throw new IllegalArgumentException("A different service is already bound to this path: " + httpService.getUri());
		}
		bindService(consumerPort);
		_httpServices.put(path, consumerPort);
		return httpService;
	}

	public void unbindHttpService(String path) {
		unbindService(_httpServices.remove(path));
	}

	public JMSConsumer bindJmsConsumer(JMSConsumer jmsConsumer) {
		JMSConsumer oldJmsConsumer = _jmsConsumer.get(jmsConsumer.getKey());
		if (oldJmsConsumer != null && !oldJmsConsumer.getUri().equals(jmsConsumer.getUri())) {
			throw new IllegalArgumentException("A different service is already bound: " + oldJmsConsumer.getUri());
		}
		bindService(jmsConsumer);
		_jmsConsumer.put(jmsConsumer.getKey(), jmsConsumer);
		return oldJmsConsumer;
	}

	public void unbindJmsConsumer(JMSConsumer jmsConsumer) {
		unbindService(_jmsConsumer.remove(jmsConsumer.getKey()));
	}

	public TimerService bindTimerService(TimerService timerService) {
		TimerService oldTimerService = _timerServices.put(timerService.getUri(), timerService);
		bindService(timerService);
		return oldTimerService;
	}

	public void unbindTimerService(TimerService timerService) {
		unbindService(_timerServices.remove(timerService.getUri()));
	}

	public void stopIngress() {
		for (TimerService timerService : _timerServices.values()) {
			timerService.close();
		}
		for (HttpConsumer httpConsumer : _httpServices.values()) {
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
		if (_mbs != null) {
			try {
				for (ObjectName objectName : _mbs.queryNames(new ObjectName(OBJECT_NAME + ",consumerType=*,uri=*"), null)) {
					unregisterMBean(objectName);
				}
				unregisterMBean(new ObjectName(OBJECT_NAME));
			} catch (JMException e) {
				// ignore
			}
		}
	}

}
