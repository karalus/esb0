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
import com.artofarc.util.DataStructures;

/**
 * Cache producers because it is expensive to create them.
 */
public final class JMSSession implements AutoCloseable {

	private static final boolean checkConnection = Boolean.parseBoolean(System.getProperty("esb0.jms.producer.checkConnection"));

	private final JMSConnectionProvider _jmsConnectionProvider;
	private final JMSConnectionData _jmsConnectionData;
	private final Session _session;
	private final Map<Destination, MessageProducer> _producers = new HashMap<>();
	private TemporaryQueue _temporaryQueue;
	private MessageConsumer _consumer;

	public JMSSession(JMSConnectionProvider jmsConnectionProvider, JMSConnectionData jmsConnectionData, Session session) {
		_jmsConnectionProvider = jmsConnectionProvider;
		_jmsConnectionData = jmsConnectionData;
		_session = session;
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
		if (checkConnection) {
			_jmsConnectionProvider.checkConnection(_jmsConnectionData);
		}
		MessageProducer producer = _producers.get(destination);
		if (producer == null) {
			producer = _session.createProducer(destination);
			_producers.put(destination, producer);
		}
		return producer;
	}

	public TemporaryQueue getTemporaryQueue() throws JMSException {
		if (_temporaryQueue == null) {
			_temporaryQueue = _session.createTemporaryQueue();
			_consumer = _session.createConsumer(_temporaryQueue);
		}
		return _temporaryQueue;
	}

	public MessageConsumer getConsumerForTemporaryQueue() {
		return _consumer;
	}

	@Override
	public void close() throws JMSException {
		if (_consumer != null) {
			_consumer.close();
			_temporaryQueue.delete();
		}
		for (Destination destination : _producers.keySet()) {
			JMSConnectionProvider.logger.info("Closing producer for " + getDestinationName(destination));
		}
		_producers.clear();
		_jmsConnectionProvider.closeSession(_jmsConnectionData, this);
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
