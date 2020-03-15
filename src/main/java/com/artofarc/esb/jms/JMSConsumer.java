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

import java.util.Enumeration;
import java.util.concurrent.Future;

import javax.jms.*;
import javax.naming.NamingException;

import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.WorkerPool;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.resource.JMSSessionFactory;
import com.artofarc.util.Closer;

public final class JMSConsumer extends ConsumerPort implements com.artofarc.esb.mbean.JMSConsumerMXBean {

	private final String _workerPool;
	private final JMSConnectionData _jmsConnectionData;
	private Destination _destination;
	private final String _queueName;
	private final String _topicName;
	private final String _subscription;
	private final String _messageSelector;
	private final JMSWorker[] _jmsWorker;
	private final long _pollInterval;

	public JMSConsumer(GlobalContext globalContext, String uri, String workerPool, JMSConnectionData jmsConnectionData, String jndiDestination, String queueName,
			String topicName, String subscription, String messageSelector, int workerCount, long pollInterval) throws NamingException, JMSException {

		super(uri);
		_workerPool = workerPool;
		_jmsConnectionData = jmsConnectionData;
		_messageSelector = globalContext.bindProperties(messageSelector);
		if (jndiDestination != null) {
			_destination = globalContext.lookup(jndiDestination);
		}
		if (_destination instanceof Queue) {
			_queueName = ((Queue) _destination).getQueueName();
			_topicName = null;
		} else if (_destination instanceof Topic) {
			_queueName = null;
			_topicName = ((Topic) _destination).getTopicName();
		} else {
			if (queueName == null && topicName == null) throw new IllegalArgumentException("One of jndiDestination, queueName or topicName must be set");
			_queueName = globalContext.bindProperties(queueName);
			_topicName = globalContext.bindProperties(topicName);
		}
		if (subscription != null) {
			if (_topicName == null) throw new IllegalArgumentException("Subscription only allowed for topics: " + getKey());
			if (workerCount != 1) throw new IllegalArgumentException("Subscriptions can only have one worker: " + getKey());
		}
		_subscription = subscription;
		_jmsWorker = new JMSWorker[workerCount];
		_pollInterval = pollInterval; 
	}

	private String getDestinationName() {
		return _queueName != null ? _queueName : _topicName;
	}

	private Destination getDestination(Session session) throws JMSException {
		if (_destination == null) {
			// All popular MOMs have no session related data in the Destination object, so we dare to cache it
			_destination = _queueName != null ? session.createQueue(_queueName) : session.createTopic(_topicName);
		}
		return _destination;
	}

	public String getKey() {
		String key = _jmsConnectionData.toString() + '|' + getDestinationName();
		if (_subscription != null) key += '|' + _subscription;
		if (_messageSelector != null) key += '|' + _messageSelector;
		return key;
	}

	public int getWorkerCount() {
		return _jmsWorker.length;
	}

	@Override
	public void init(GlobalContext globalContext) throws JMSException {
		WorkerPool workerPool = globalContext.getWorkerPool(_workerPool);
		// distribute evenly over poll interval
		long initialDelay = _pollInterval / _jmsWorker.length;
		for (int i = 0; i < _jmsWorker.length; ++i) {
			_jmsWorker[i] = _pollInterval > 0L ? new JMSPollingWorker(workerPool, initialDelay * i) : new JMSWorker(workerPool);
		}
		JMSConnectionProvider jmsConnectionProvider = workerPool.getPoolContext().getResourceFactory(JMSConnectionProvider.class);
		jmsConnectionProvider.registerJMSConsumer(_jmsConnectionData, this, super.isEnabled());
		resume();
		if (super.isEnabled()) {
			try {
				enable(true);
			} catch (JMSException e) {
				logger.error("Could not enable " + getKey(), e);
			}
		}
	}

	void resume() throws JMSException {
		for (JMSWorker jmsWorker : _jmsWorker) {
			jmsWorker.open();
		}
	}

	@Override
	public boolean isEnabled() {
		return _jmsWorker[0]._messageConsumer != null;
	}

	@Override
	public void enable(boolean enable) throws JMSException {
		for (int i = 0; i < _jmsWorker.length;) {
			JMSWorker jmsWorker = _jmsWorker[i++];
			if (enable) {
				jmsWorker.startListening();
			} else {
				jmsWorker.stopListening();
			}
		}
	}

	void suspend() throws Exception {
		for (JMSWorker jmsWorker : _jmsWorker) {
			jmsWorker.close();
		}
	}

	@Override
	public void close() throws Exception {
		for (JMSWorker jmsWorker : _jmsWorker) {
			jmsWorker.close();
			jmsWorker._context.close();
		}
		JMSConnectionProvider jmsConnectionProvider = _jmsWorker[0]._workerPool.getPoolContext().getResourceFactory(JMSConnectionProvider.class);
		jmsConnectionProvider.unregisterJMSConsumer(_jmsConnectionData, this);
	}

	public static void fillESBMessage(ESBMessage esbMessage, Message message) throws JMSException {
		if (message instanceof BytesMessage) {
			BytesMessage bytesMessage = (BytesMessage) message;
			byte[] ba = new byte[(int) bytesMessage.getBodyLength()];
			bytesMessage.readBytes(ba);
			esbMessage.reset(BodyType.BYTES, ba, message.getStringProperty(ESBConstants.Charset));
		} else if (message instanceof TextMessage) {
			TextMessage textMessage = (TextMessage) message;
			esbMessage.reset(BodyType.STRING, textMessage.getText());
		} else {
			esbMessage.reset(BodyType.INVALID, null);
		}
		message.clearBody();
		esbMessage.putVariable(ESBConstants.JMSMessageID, message.getJMSMessageID());
		esbMessage.putVariable(ESBConstants.JMSCorrelationID, message.getJMSCorrelationID());
		esbMessage.putVariable(ESBConstants.JMSReplyTo, message.getJMSReplyTo());
		esbMessage.putVariable(ESBConstants.JMSTimestamp, message.getJMSTimestamp());
		for (@SuppressWarnings("unchecked")
		Enumeration<String> propertyNames = message.getPropertyNames(); propertyNames.hasMoreElements();) {
			String propertyName = propertyNames.nextElement();
			esbMessage.putHeader(propertyName, message.getObjectProperty(propertyName));
		}
	}

	class JMSWorker implements MessageListener {
		final WorkerPool _workerPool;
		final Context _context;
		volatile Session _session;
		volatile MessageConsumer _messageConsumer;

		JMSWorker(WorkerPool workerPool) {
			_workerPool = workerPool;
			_context = new Context(workerPool.getPoolContext());
		}

		final void open() throws JMSException {
			JMSSessionFactory jmsSessionFactory = _context.getResourceFactory(JMSSessionFactory.class);
			_session = jmsSessionFactory.getResource(_jmsConnectionData, true).getSession();
		}

		final void initMessageConsumer() throws JMSException {
			if (_messageConsumer == null && _session != null) {
				if (_subscription != null) {
					_messageConsumer = _session.createDurableSubscriber((Topic) getDestination(_session), _subscription, _messageSelector, false);
				} else {
					_messageConsumer = _session.createConsumer(getDestination(_session), _messageSelector);
				}
			}
		}

		void startListening() throws JMSException {
			initMessageConsumer();
			if (_messageConsumer != null) {
				try {
					_messageConsumer.setMessageListener(this);
				} catch (JMSException e) {
					stopListening();
					throw e;
				}
			}
		}

		void stopListening() {
			if (_messageConsumer != null) {
				try {
					if (JMSConnectionProvider.closeWithTimeout > 0) {
						Closer closer = new Closer(_workerPool.getExecutorService());
						// Oracle AQ sometimes waits forever in close()
						closer.closeWithTimeout(_messageConsumer, JMSConnectionProvider.closeWithTimeout, getKey());
					} else {
						_messageConsumer.close();
					}
				} catch (Exception e) {
					// ignore
				}
				_messageConsumer = null;
			}
		}

		final void close() throws Exception {
			stopListening();
			_session = null;
			JMSSessionFactory jmsSessionFactory = _context.getResourceFactory(JMSSessionFactory.class);
			jmsSessionFactory.close(_jmsConnectionData);
		}

		@Override
		public void onMessage(Message message) {
			// monitor threads not started by us but by the JMS provider 
			_workerPool.addThread(Thread.currentThread(), getDestinationName());
			ESBMessage esbMessage = new ESBMessage(BodyType.INVALID, null);
			esbMessage.putVariable(ESBConstants.JMSOrigin, getDestinationName());
			try {
				fillESBMessage(esbMessage, message);
				try {
					processInternal(_context, esbMessage);
					_session.commit();
				} catch (Exception e) {
					logger.info("Rolling back for " + getKey(), e);
					_session.rollback();
				}
			} catch (JMSException e) {
				throw new RuntimeException(e);
			}
		}
	}

	// https://stackoverflow.com/questions/22040361/too-much-selects-when-using-oracle-aq-and-spring-jms
	class JMSPollingWorker extends JMSWorker implements Runnable {
		final long _initialDelay;
		volatile Future<?> _poller;

		JMSPollingWorker(WorkerPool workerPool, long initialDelay) {
			super(workerPool);
			_initialDelay = initialDelay;
		}

		void startListening() throws JMSException {
			initMessageConsumer();
			if (_messageConsumer != null) {
				_poller = _workerPool.getExecutorService().submit(this);
			}
		}

		void stopListening() {
			if (_poller != null) {
				_poller.cancel(true);
				_poller = null;
				super.stopListening();
			}
		}

		@Override
		public void run() {
			try {
				Thread.sleep(_initialDelay);
				for (long last = System.nanoTime();;) {
					Message message = _messageConsumer.receiveNoWait();
					if (message != null) {
						onMessage(message);
					} else {
						Thread.sleep(_pollInterval - (System.nanoTime() - last) / 1000000L % _pollInterval);
						last = System.nanoTime();
					}
				}
			} catch (JMSException e) {
				JMSConnectionProvider jmsConnectionProvider = _workerPool.getPoolContext().getResourceFactory(JMSConnectionProvider.class);
				jmsConnectionProvider.getExceptionListener(_jmsConnectionData).onException(e);
			} catch (InterruptedException e) {
				// stopListening
			}
		}
	}

}
