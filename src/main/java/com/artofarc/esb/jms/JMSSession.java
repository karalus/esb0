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
package com.artofarc.esb.jms;

import java.util.HashMap;

import javax.jms.*;

import com.artofarc.esb.context.PoolContext;
import com.artofarc.util.Closer;

/**
 * Cache producers because it is expensive to create them.
 */
public final class JMSSession implements AutoCloseable {

	private final PoolContext _poolContext;
	private final JMSConnectionData _jmsConnectionData;
	private final Session _session;
	private final HashMap<Destination, MessageProducer> _producers = new HashMap<>();
	private TemporaryQueue _temporaryQueue;
	private MessageConsumer _consumer;

	public JMSSession(PoolContext poolContext, JMSConnectionData jmsConnectionData, Session session) {
		_poolContext = poolContext;
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
				if (queue.getQueueName().equals(queueName)) {
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
				if (topic.getTopicName().equals(topicName)) {
					return topic;
				}
			}
		}
		return _session.createTopic(topicName);
	}

	public MessageProducer createProducer(Destination destination) throws JMSException {
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
	public void close() throws Exception {
		if (_consumer != null) {
			_consumer.close();
			_temporaryQueue.delete();
		}
		_producers.clear();
		if (JMSConnectionProvider.closeWithTimeout > 0) {
			Closer closer = new Closer(_poolContext.getWorkerPool().getExecutorService());
			// Oracle AQ sometimes waits forever in close()
			closer.closeWithTimeout(_session, JMSConnectionProvider.closeWithTimeout, _jmsConnectionData.toString());
		} else {
			_session.close();
		}
	}

}
