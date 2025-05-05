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
package com.artofarc.esb.jms;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.jms.*;

import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.resource.JMSSessionFactory;
import com.artofarc.util.DataStructures;

/**
 * Cache producers because it is expensive to create them.
 */
public final class JMSSession implements AutoCloseable {

	private static final boolean checkConnection = Boolean.parseBoolean(System.getProperty("esb0.jms.producer.checkConnection"));

	private final JMSConnectionData _jmsConnectionData;
	private final JMSConnectionProvider.JMSConnectionGuard _jmsConnection;
	private final boolean _transacted;
	private final Map<Destination, MessageProducer> _producers = new HashMap<>();
	private Session _session;
	private TemporaryQueue _temporaryQueue;
	private MessageConsumer _consumer;

	public JMSSession(JMSSessionFactory jmsSessionFactory, JMSConnectionData jmsConnectionData, boolean transacted) throws JMSException {
		_jmsConnectionData = jmsConnectionData;
		_transacted = transacted;
		_jmsConnection = jmsSessionFactory.getJMSConnectionProvider().getResource(jmsConnectionData);
		createSession();
		_jmsConnection.addJMSSessionFactory(jmsSessionFactory);
	}

	private void createSession() throws JMSException {
		_session = _jmsConnection.getConnection().createSession(_transacted, _transacted ? Session.SESSION_TRANSACTED : Session.AUTO_ACKNOWLEDGE);
	}

	public JMSConnectionData getJMSConnectionData() {
		return _jmsConnectionData;
	}

	public boolean isTransacted() {
		return _transacted;
	}

	public boolean isConnected() {
		return _jmsConnection.isConnected();
	}

	public Session getSession() {
		return _session;
	}

	public Queue createQueue(String queueName) throws JMSException {
		for (Destination destination : _producers.keySet()) {
			if (destination instanceof Queue) {
				Queue queue = (Queue) destination;
				if (queueName.equals(queue.getQueueName())) {
					return queue;
				}
			}
		}
		return _session.createQueue(queueName);
	}

	public Topic createTopic(String topicName) throws JMSException {
		for (Destination destination : _producers.keySet()) {
			if (destination instanceof Topic) {
				Topic topic = (Topic) destination;
				if (topicName.equals(topic.getTopicName())) {
					return topic;
				}
			}
		}
		return _session.createTopic(topicName);
	}

	public MessageProducer createProducer(Destination destination) throws JMSException {
		if (checkConnection && !isConnected()) {
			throw new JMSException("Currently reconnecting " + _jmsConnectionData);
		}
		MessageProducer producer = _producers.get(destination);
		if (producer == null) {
			JMSConnectionProvider.logger.info("Creating new producer for " + getDestinationName(destination).getValue());
			producer = _session.createProducer(destination);
			_producers.put(destination, producer);
		}
		return producer;
	}

	public TemporaryQueue getTemporaryQueue() throws JMSException {
		if (_temporaryQueue == null) {
			_temporaryQueue = _session.createTemporaryQueue();
		}
		return _temporaryQueue;
	}

	public MessageConsumer getConsumerForTemporaryQueue() throws JMSException {
		if (_consumer == null) {
			_consumer = _session.createConsumer(_temporaryQueue);
		}
		return _consumer;
	}

	public void setDeliveryDelay(MessageProducer producer, Message message, long deliveryDelay) throws JMSException {
		ConnectionMetaData connectionMetaData = _jmsConnection.getConnectionMetaData();
		if (connectionMetaData.getJMSMajorVersion() > 1) {
			producer.setDeliveryDelay(deliveryDelay);
		} else if (deliveryDelay > 0) {
			String jmsProviderName = connectionMetaData.getJMSProviderName();
			switch (jmsProviderName) {
			case "ActiveMQ":
				// https://stackoverflow.com/questions/58609188/delaying-messages-in-activemq
				message.setLongProperty("AMQ_SCHEDULED_DELAY", deliveryDelay);
				break;
			case "Oracle":
				message.setIntProperty("JMS_OracleDelay", (int) ((deliveryDelay + 999) / 1000));
				break;
			default:
				throw new JMSException("Delivery delay not implemented for " + jmsProviderName);
			}
		}
	}

	public void send(MessageProducer producer, Message message, int deliveryMode, int priority, long timeToLive) throws JMSException {
		try {
			producer.send(message, deliveryMode, priority, timeToLive);
		} catch (JMSException e) {
			if ("Oracle".equals(_jmsConnection.getConnectionMetaData().getJMSProviderName())) {
				close();
				createSession();
				// Alternatively
				//_jmsConnection.scheduleReconnect();
			}
			throw e;
		}
	}

	@Override
	public void close() throws JMSException {
		for (Destination destination : _producers.keySet()) {
			JMSConnectionProvider.logger.info("Closing producer for " + getDestinationName(destination));
		}
		_producers.clear();
		if (_temporaryQueue != null) {
			try {
				if (_consumer != null) {
					_consumer.close();
				}
				_temporaryQueue.delete();
			} catch (JMSException e) {
				JMSConnectionProvider.logger.warn("Could not delete " + _temporaryQueue.getQueueName(), e);
			}
			_consumer = null;
			_temporaryQueue = null;
		}
		_jmsConnection.closeSession(this);
		_session = null;
	}

	public Collection<String> getProducerDestinations() throws JMSException {
		ArrayList<String> result = new ArrayList<>();
		for (Destination destination : _producers.keySet()) {
			result.add(String.valueOf(getDestinationName(destination)));
		}
		return result;
	}

	public static Map.Entry<String, String> getDestinationName(Destination destination) throws JMSException {
		if (destination instanceof Queue) {
			return DataStructures.createEntry(ESBConstants.QueueName, ((Queue) destination).getQueueName());
		}
		if (destination instanceof Topic) {
			return DataStructures.createEntry(ESBConstants.TopicName, ((Topic) destination).getTopicName());
		}
		return null;
	}

}
