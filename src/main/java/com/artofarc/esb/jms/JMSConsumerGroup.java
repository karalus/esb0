/*
 * Copyright 2024 Andre Karalus
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
package com.artofarc.esb.jms;

import java.util.ArrayList;
import java.util.List;

import javax.xml.datatype.XMLGregorianCalendar;

import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.Registry;
import com.artofarc.esb.action.Action;
import com.artofarc.esb.context.GlobalContext;
import com.sun.xml.xsom.XSSchemaSet;

public class JMSConsumerGroup extends ConsumerPort {

	private final List<JMSConsumer> _group;

	public JMSConsumerGroup(GlobalContext globalContext, String uri, String workerPool, List<JMSConnectionData> jmsConnectionDataList, String jndiDestination, String queueName,
			String topicName, XSSchemaSet schemaSet, String rootElement, String subscription, boolean noLocal, boolean shared, String messageSelector, int workerCount, int minWorkerCount,
			int batchSize, int batchTime, long pollInterval, String timeUnit, XMLGregorianCalendar at) throws Exception {

		super(uri);
		_group = new ArrayList<>(jmsConnectionDataList.size());
		for (JMSConnectionData jmsConnectionData : jmsConnectionDataList) {
			JMSConsumer jmsConsumer = new JMSConsumer(globalContext, uri, workerPool, jmsConnectionData, jndiDestination, queueName, topicName, schemaSet, rootElement, subscription, noLocal,
					shared, messageSelector, workerCount, minWorkerCount, batchSize, batchTime, pollInterval, timeUnit, at);
			globalContext.checkBindJmsConsumer(jmsConsumer);
			_group.add(jmsConsumer);
		}
	}

	public List<JMSConsumer> getGroup() {
		return _group;
	}

	@Override
	public void setEnabled(boolean enabled) {
		for (JMSConsumer jmsConsumer : _group) {
			jmsConsumer.setEnabled(enabled);
		}
	}

	@Override
	public void setStartAction(Action action) {
		for (JMSConsumer jmsConsumer : _group) {
			jmsConsumer.setStartAction(action);
		}
	}

	@Override
	public boolean needsSyncClose(ConsumerPort oldConsumerPort) {
		if (oldConsumerPort instanceof JMSConsumer) {
			for (JMSConsumer jmsConsumer : _group) {
				if (jmsConsumer.getKey().equals(((JMSConsumer) oldConsumerPort).getKey())) return true;
			}
		}
		if (oldConsumerPort instanceof JMSConsumerGroup) {
			for (JMSConsumer jmsConsumer : _group) {
				for (JMSConsumer jmsConsumer2 : ((JMSConsumerGroup) oldConsumerPort).getGroup()) {
					if (jmsConsumer.getKey().equals(jmsConsumer2.getKey())) return true;
				}
			}
		}
		return false;
	}

	@Override
	public void close() {
		for (JMSConsumer jmsConsumer : _group) {
			jmsConsumer.close();
		}
	}

	@Override
	public void bind(Registry registry) {
		for (JMSConsumer jmsConsumer : _group) {
			registry.registerMBean(jmsConsumer, jmsConsumer.getMBeanPostfix());
			registry.registerJmsConsumer(jmsConsumer);
		}
	}

	@Override
	public void unbind(Registry registry) {
		for (JMSConsumer jmsConsumer : _group) {
			registry.unbindJmsConsumer(jmsConsumer);
		}
	}
}
