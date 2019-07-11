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
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

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

public final class JMSConsumer extends ConsumerPort implements AutoCloseable, com.artofarc.esb.mbean.JMSConsumerMXBean {

	private final String _workerPool;
	private final String _jndiConnectionFactory;
	private Destination _destination;
	private final String _queueName;
	private final String _topicName;
	private final String _subscription;
	private final String _messageSelector;
	private final JMSWorker[] _jmsWorker;
	private final long _pollInterval;
	private final AtomicLong completedTaskCount = new AtomicLong();

	public JMSConsumer(GlobalContext globalContext, String uri, String workerPool, String jndiConnectionFactory, String jndiDestination, String queueName,
			String topicName, String subscription, String messageSelector, int workerCount, long pollInterval) throws NamingException, JMSException {

		super(uri);
		_workerPool = workerPool;
		_jndiConnectionFactory = jndiConnectionFactory;
		_messageSelector = messageSelector != null ? bindProperties(messageSelector, System.getProperties()) : null;
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
			_queueName = queueName;
			_topicName = topicName;
		}
		if (subscription != null) {
			if (_topicName == null) throw new IllegalArgumentException("Subscription only allowed for topics: " + getKey());
			if (workerCount != 1) throw new IllegalArgumentException("Subscriptions can only have one worker: " + getKey());
		}
		_subscription = subscription;
		_jmsWorker = new JMSWorker[workerCount];
		_pollInterval = pollInterval; 
	}

	private static String bindProperties(String exp, Map<?, ?> props) {
		StringBuilder builder = new StringBuilder();
		for (int pos = 0;;) {
			int i = exp.indexOf("${", pos);
			if (i < 0) {
				builder.append(exp.substring(pos));
				break;
			}
			builder.append(exp.substring(pos, i));
			int j = exp.indexOf('}', i);
			if (j < 0) throw new IllegalArgumentException("Matching } is missing");
			String name = exp.substring(i + 2, j);
			Object value = props.get(name);
			if (value == null) {
				throw new NullPointerException(name + " is not set");
			}
			builder.append(value);
			pos = j + 1;
		}
		return builder.toString();
	}

	private String getDestinationName() {
		return _queueName != null ? _queueName : _topicName;
	}

	public String getKey() {
		String key = _jndiConnectionFactory + '|' + getDestinationName();
		if (_subscription != null) key += '|' + _subscription;
		if (_messageSelector != null) key += '|' + _messageSelector;
		return key;
	}

	public int getWorkerCount() {
		return _jmsWorker.length;
	}

	public long getCompletedTaskCount() {
		return completedTaskCount.get();
	}

	@Override
	public void init(GlobalContext globalContext) throws Exception {
		WorkerPool workerPool = globalContext.getWorkerPool(_workerPool);
		for (int i = 0; i < _jmsWorker.length; ++i) {
			_jmsWorker[i] = _pollInterval > 0L ? new JMSPollingWorker(workerPool) : new JMSWorker(workerPool);
		}
		workerPool.getPoolContext().getJMSConnectionProvider().registerJMSConsumer(_jndiConnectionFactory, this, super.isEnabled());
		resume();
		if (super.isEnabled()) {
			enable(true);
		}
	}

	void resume() throws Exception {
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
				if (_pollInterval > 0L && i < _jmsWorker.length) {
					// distribute evenly over poll interval
					try {
						Thread.sleep(_pollInterval / _jmsWorker.length);
					} catch (InterruptedException e) {
						// ignore
					}
				}
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
			esbMessage.getHeaders().put(propertyName, message.getObjectProperty(propertyName));
		}
	}

	class JMSWorker implements MessageListener {
		final WorkerPool _workerPool;
		final Context _context;
		volatile Session _session;
		volatile MessageConsumer _messageConsumer;

		JMSWorker(WorkerPool workerPool) throws Exception {
			_workerPool = workerPool;
			_context = new Context(workerPool.getPoolContext());
		}

		final void open() throws Exception {
			JMSSessionFactory jmsSessionFactory = _context.getResourceFactory(JMSSessionFactory.class);
			_session = jmsSessionFactory.getResource(_jndiConnectionFactory, true).getSession();
			if (_destination == null) {
				try {
					_destination = _queueName != null ? _session.createQueue(_queueName) : _session.createTopic(_topicName);
				} catch (JMSException e) {
					throw new InvalidDestinationException(e.getMessage());
				}
			}
		}

		final void initMessageConsumer() throws JMSException {
			if (_messageConsumer == null && _session != null && _destination != null) {
				if (_subscription != null) {
					_messageConsumer = _session.createDurableSubscriber((Topic) _destination, _subscription, _messageSelector, false);
				} else {
					_messageConsumer = _session.createConsumer(_destination, _messageSelector);
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
			jmsSessionFactory.close(_jndiConnectionFactory);
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
				completedTaskCount.incrementAndGet();
			} catch (JMSException e) {
				throw new RuntimeException(e);
			}
		}
	}

	// https://stackoverflow.com/questions/22040361/too-much-selects-when-using-oracle-aq-and-spring-jms
	class JMSPollingWorker extends JMSWorker implements Runnable {
		volatile Future<?> _poller;

		JMSPollingWorker(WorkerPool workerPool) throws Exception {
			super(workerPool);
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
				_workerPool.getPoolContext().getJMSConnectionProvider().getExceptionListener(_jndiConnectionFactory).onException(e);
			} catch (InterruptedException e) {
				// stopListening
			}
		}
	}

}
