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

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

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
import com.artofarc.util.ConcurrentResourcePool;
import com.artofarc.util.IOUtils;
import com.artofarc.util.ReflectionUtils;

public final class JMSConsumer extends ConsumerPort implements Comparable<JMSConsumer>, com.artofarc.esb.mbean.JMSConsumerMXBean {

	static class BytesMessageInputStream extends InputStream implements IOUtils.PredictableInputStream{
		final BytesMessage _bytesMessage;
		long available;

		BytesMessageInputStream(BytesMessage bytesMessage) throws JMSException {
			_bytesMessage = bytesMessage;
			available = bytesMessage.getBodyLength();
		}

		@Override
		public long length() {
			return available;
		}

		@Override
		public int available() {
			return available > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) available;
		}

		@Override
		public int read() throws IOException {
			if (available == 0L) {
				return -1;
			}
			try {
				final int c = _bytesMessage.readUnsignedByte();
				--available;
				return c;
			} catch (JMSException e) {
				throw new IOException(e);
			}
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (available == 0L) {
				return -1;
			}
			try {
				if (off == 0) {
					len = _bytesMessage.readBytes(b, len);
				} else {
					final byte[] ba = new byte[len];
					len = _bytesMessage.readBytes(ba, len);
					if (len > 0) {
						System.arraycopy(ba, 0, b, off, len);
					}
				}
				if (len > 0 && (available -= len) == 0L) {
					_bytesMessage.clearBody();
				}
				return len;
			} catch (JMSException e) {
				throw new IOException(e);
			}
		}
	}

	private final String _workerPoolName;
	private WorkerPool _workerPool; 
	private final JMSConnectionData _jmsConnectionData;
	private Destination _destination;
	private final String _queueName;
	private final String _topicName;
	private final String _subscription;
	private final boolean _shared;
	private final String _messageSelector;
	private final JMSWorker[] _jmsWorker;
	private final long _pollInterval;
	private final Integer _maximumRetries;
	private final ConcurrentResourcePool<AtomicInteger, String, Void, RuntimeException> _retries;
	private final Long _redeliveryDelay;
	private final long _rampUpThreshold = 1000L;
	private final long _rampUpDelay = 2000L;
	private final long _rampDownThreshold = 100L;
	private final long _rampDownDelay = 10000L;
	private final int _minWorkerCount;
	private final int _significance = 10;
	private long _lastControlChange;
	private volatile boolean _operating;
	private volatile int _workerCount;
	private volatile long _currentSentReceiveDelay;
	private volatile long _lastChangeOfState;
	private Future<?> _control;

	public JMSConsumer(GlobalContext globalContext, String uri, String workerPool, JMSConnectionData jmsConnectionData, String jndiDestination, String queueName, String topicName, String subscription,
			boolean shared, String messageSelector, int workerCount, int minWorkerCount, long pollInterval, Integer maximumRetries, Long redeliveryDelay) throws NamingException, JMSException {

		super(uri);
		_workerPoolName = workerPool;
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
		_shared = shared;
		_jmsWorker = new JMSWorker[workerCount];
		_pollInterval = pollInterval;
		_maximumRetries = maximumRetries;
		if (maximumRetries != null) {
			_retries = new ConcurrentResourcePool<AtomicInteger, String, Void, RuntimeException>() {
				
				@Override
				protected AtomicInteger createResource(String descriptor, Void param) {
					return new AtomicInteger();
				}
			};
		} else {
			_retries = null;
		}
		_redeliveryDelay = redeliveryDelay;
		_minWorkerCount = minWorkerCount;
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
		if (_workerPoolName != null) {
			key += '@' + _workerPoolName;
		}
		return key;
	}

	public int getWorkerCount() {
		return _workerCount;
	}

	public Date getLastChangeOfState() {
		return new Date(_lastChangeOfState);
	}

	public long getCurrentSentReceiveDelay() {
		return _currentSentReceiveDelay;
	}

	public int getRetries() {
		return _retries != null ? _retries.getResourceDescriptors().size() : -1;
	}

	@Override
	public int compareTo(JMSConsumer o) {
		return getKey().compareTo(o.getKey());
	}

	@Override
	public void init(GlobalContext globalContext) throws JMSException {
		_workerPool = globalContext.getWorkerPool(_workerPoolName);
		// distribute evenly over poll interval
		long initialDelay = _pollInterval / _jmsWorker.length;
		for (; _workerCount < _minWorkerCount; ++_workerCount) {
			_jmsWorker[_workerCount] = _pollInterval > 0L ? new JMSPollingWorker(initialDelay * _workerCount) : new JMSWorker();
		}
		JMSConnectionProvider jmsConnectionProvider = _workerPool.getPoolContext().getResourceFactory(JMSConnectionProvider.class);
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

	public synchronized void controlJMSWorkerPool(long sentReceiveDelay, long receiveTimestamp) {
		boolean notBusy = _control == null || _control.isDone();
		if (_operating && _workerCount < _jmsWorker.length && sentReceiveDelay > _rampUpThreshold && notBusy && _lastControlChange + _rampUpDelay < receiveTimestamp) {
			// add Worker
			final JMSWorker jmsWorker = _pollInterval > 0L ? new JMSPollingWorker(0L) : new JMSWorker();
			_control = _workerPool.getExecutorService().submit(() -> {
				try {
					jmsWorker.open();
					jmsWorker.startListening();
				} catch (Exception e) {
					logger.error("Could not add JMSWorker", e);
				}
			});
			_jmsWorker[_workerCount++] = jmsWorker;
			_lastControlChange = System.currentTimeMillis();
		}
		if (_operating && _workerCount > _minWorkerCount && sentReceiveDelay < _rampDownThreshold && notBusy && _lastControlChange + _rampDownDelay < receiveTimestamp) {
			// remove Worker
			final JMSWorker jmsWorker = _jmsWorker[--_workerCount];
			_control = _workerPool.getExecutorService().submit(() -> {
				try {
					jmsWorker.close();
				} catch (Exception e) {
					logger.debug("Could not close JMSWorker", e);
				}
				jmsWorker._context.close();
			});
			_jmsWorker[_workerCount] = null;
			_lastControlChange = System.currentTimeMillis();
		}
	}

	void resume() throws JMSException {
		if (_retries != null) {
			try {
				_retries.reset();
			} catch (Exception e) {
				// ignore
			}
		}
		for (int i = 0; i < _workerCount;) {
			JMSWorker jmsWorker = _jmsWorker[i++];
			jmsWorker.open();
		}
	}

	boolean isToBeEnabled() {
		return isEnabled() || _jmsWorker[0]._session == null && super.isEnabled();
	}

	@Override
	public boolean isEnabled() {
		return _jmsWorker[0]._messageConsumer != null;
	}

	@Override
	public void enable(boolean enable) throws JMSException {
		int workerCount;
		synchronized (this) {
			_operating = enable;
			if (_control != null) {
				await(_control);
				_control = null;
			}
			workerCount = _workerCount;
			if (!enable) {
				_workerCount = _minWorkerCount;
			}
		}
		try {
			Future<?>[] futures = new Future<?>[workerCount];
			for (int i = 0; i < workerCount; ++i) {
				final JMSWorker jmsWorker = _jmsWorker[i];
				Callable<Void> task;
				if (enable) {
					task = () -> { jmsWorker.startListening(); return null; };
				} else {
					task = i < _minWorkerCount ? () -> { jmsWorker.stopListening(); return null; } : () -> { jmsWorker.close(); return null; };
				}
				futures[i] = _workerPool.getExecutorService().submit(task);
			}
			for (int i = 0; i < workerCount; ++i) {
				await(futures[i]);
			}
		} finally {
			_operating = true;
		}
		_lastChangeOfState = System.currentTimeMillis();
	}

	private void await(Future<?> future) throws JMSException {
		try {
			future.get();
		} catch (InterruptedException e) {
			logger.error("Unexpected cancellation during control of " + getKey());
		} catch (ExecutionException e) {
			if (e.getCause() instanceof JMSException) {
				throw (JMSException) e.getCause();
			}
			logger.error("Unexpected Exception during control of " + getKey(), e);
		}
	}

	void suspend() throws Exception {
		enable(false);
		for (int i = 0; i < _workerCount;) {
			JMSWorker jmsWorker = _jmsWorker[i++];
			jmsWorker.close();
		}
	}

	@Override
	public void close() throws Exception {
		for (int i = 0; i < _workerCount;) {
			JMSWorker jmsWorker = _jmsWorker[i++];
			// close silently
			jmsWorker.close();
			jmsWorker._context.close();
		}
		JMSConnectionProvider jmsConnectionProvider = _workerPool.getPoolContext().getResourceFactory(JMSConnectionProvider.class);
		jmsConnectionProvider.unregisterJMSConsumer(_jmsConnectionData, this);
	}

	public static void fillESBMessage(ESBMessage esbMessage, Message message) throws JMSException {
		if (message instanceof BytesMessage) {
			esbMessage.reset(BodyType.INPUT_STREAM, new BytesMessageInputStream((BytesMessage) message), message.getStringProperty(ESBConstants.Charset));
		} else if (message instanceof TextMessage) {
			TextMessage textMessage = (TextMessage) message;
			esbMessage.reset(BodyType.STRING, textMessage.getText());
			message.clearBody();
		}
		esbMessage.putVariable(ESBConstants.JMSMessageID, message.getJMSMessageID());
		esbMessage.putVariable(ESBConstants.JMSTimestamp, message.getJMSTimestamp());
		esbMessage.putVariable(ESBConstants.JMSExpiration, message.getJMSExpiration());
		esbMessage.putVariableIfNotNull(ESBConstants.JMSType, message.getJMSType());
		esbMessage.putVariableIfNotNull(ESBConstants.JMSCorrelationID, message.getJMSCorrelationID());
		esbMessage.putVariableIfNotNull(ESBConstants.JMSReplyTo, message.getJMSReplyTo());
		Map.Entry<String, String> destinationName = JMSSession.getDestinationName(message.getJMSDestination());
		if (destinationName != null) {
			esbMessage.putVariable(destinationName.getKey(), destinationName.getValue());
		}
		for (@SuppressWarnings("unchecked")
		Enumeration<String> propertyNames = message.getPropertyNames(); propertyNames.hasMoreElements();) {
			String propertyName = propertyNames.nextElement();
			esbMessage.putHeader(propertyName, message.getObjectProperty(propertyName));
		}
	}

	class JMSWorker implements MessageListener {
		final Context _context = new Context(_workerPool.getPoolContext());
		volatile Session _session;
		volatile MessageConsumer _messageConsumer;

		final void open() throws JMSException {
			JMSSessionFactory jmsSessionFactory = _context.getResourceFactory(JMSSessionFactory.class);
			_session = jmsSessionFactory.getResource(_jmsConnectionData, true).getSession();
		}

		final void initMessageConsumer() throws JMSException {
			if (_messageConsumer == null && _session != null) {
				if (_subscription != null) {
					if (_shared) {
						_messageConsumer = _session.createSharedDurableConsumer((Topic) getDestination(_session), _subscription, _messageSelector);
					} else {
						_messageConsumer = _session.createDurableSubscriber((Topic) getDestination(_session), _subscription, _messageSelector, false);
					}
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
						// Oracle AQ sometimes waits forever in close()
						Closer.closeWithTimeout(_messageConsumer, _workerPool.getExecutorService(), JMSConnectionProvider.closeWithTimeout, getKey(), JMSException.class);
					} else {
						_messageConsumer.close();
					}
				} catch (Exception e) {
					// ignore
					logger.debug(getKey(), e);
				} finally {
					_messageConsumer = null;
				}
			}
		}

		final void close() throws Exception {
			stopListening();
			_session = null;
			JMSConnectionProvider jmsConnectionProvider = _context.getPoolContext().getResourceFactory(JMSConnectionProvider.class);
			JMSSessionFactory jmsSessionFactory = _context.getResourceFactory(JMSSessionFactory.class);
			jmsConnectionProvider.unregisterJMSSessionFactory(_jmsConnectionData, jmsSessionFactory);
			jmsSessionFactory.close(_jmsConnectionData);
		}

		@Override
		public void onMessage(Message message) {
			// monitor threads not started by us but by the JMS provider 
			_workerPool.addThread(Thread.currentThread(), getDestinationName());
			try {
				processMessage(message);
			} catch (Exception e) {
				throw ReflectionUtils.convert(e, RuntimeException.class);
			}
		}

		final boolean processMessage(Message message) throws JMSException, InterruptedException {
			ESBMessage esbMessage = new ESBMessage(BodyType.INVALID, null);
			esbMessage.putVariable(ESBConstants.JMSOrigin, getDestinationName());
			fillESBMessage(esbMessage, message);
			try {
				processInternal(_context, esbMessage);
				_session.commit();
				if (message.getJMSRedelivered() ) {
					if (_retries != null) {
						_retries.removeResource(message.getJMSMessageID());
					}
				} else {
					long receiveTimestamp = esbMessage.getVariable(ESBConstants.initialTimestamp);
					long sentReceiveDelay = (_currentSentReceiveDelay * _significance + receiveTimestamp - message.getJMSTimestamp()) / (_significance + 1);
					controlJMSWorkerPool(_currentSentReceiveDelay = sentReceiveDelay, receiveTimestamp);
				}
				return true;
			} catch (Exception e) {
				if (_redeliveryDelay != null) {
					Thread.sleep(_redeliveryDelay);
				}
				logger.info("Rolling back for " + getKey(), e);
				_session.rollback();
				if (_retries != null) {
					AtomicInteger retries = _retries.getResource(message.getJMSMessageID());
					if (retries.incrementAndGet() > _maximumRetries) {
						try {
							enable(false);
						} catch (JMSException je) {
							logger.error("Failed to disable after reaching maximumRetries: " + getKey(), je);
						}
					}
				}
				return false;
			}
		}
	}

	// https://stackoverflow.com/questions/22040361/too-much-selects-when-using-oracle-aq-and-spring-jms
	class JMSPollingWorker extends JMSWorker implements Runnable {
		final long _initialDelay;
		volatile Future<?> _poller;

		JMSPollingWorker(long initialDelay) {
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
					if (message == null || !processMessage(message)) {
						Thread.sleep(_pollInterval - (System.nanoTime() - last) / 1000000L % _pollInterval);
						last = System.nanoTime();
					}
				}
			} catch (JMSException e) {
				JMSConnectionProvider jmsConnectionProvider = _context.getPoolContext().getResourceFactory(JMSConnectionProvider.class);
				jmsConnectionProvider.getExceptionListener(_jmsConnectionData).onException(e);
			} catch (InterruptedException e) {
				// stopListening
			}
		}
	}

}
