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

import java.util.Date;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.jms.*;
import javax.management.ObjectName;
import javax.naming.NamingException;
import javax.xml.datatype.XMLGregorianCalendar;

import com.artofarc.esb.SchedulingConsumerPort;
import com.artofarc.esb.Trend;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.resource.JMSSessionFactory;
import com.artofarc.util.Closer;
import com.artofarc.util.ReflectionUtils;

public final class JMSConsumer extends SchedulingConsumerPort implements Comparable<JMSConsumer>, com.artofarc.esb.mbean.JMSConsumerMXBean {

	private final JMSConnectionData _jmsConnectionData;
	private Destination _destination;
	private final String _queueName;
	private final String _topicName;
	private final String _subscription;
	private final boolean _noLocal;
	private final boolean _shared;
	private final String _messageSelector;
	private final JMSWorker[] _jmsWorker;
	private final long _rampUpThreshold = 1000L;
	private final long _rampUpDelay = 2000L;
	private final long _rampDownThreshold = 100L;
	private final long _rampDownDelay = 10000L;
	private final int _minWorkerCount;
	private long _lastControlChange;
	private volatile boolean _operating;
	private volatile int _workerCount;
	private final Trend _sentReceiveDelay = new Trend(10L);
	private volatile long _lastChangeOfState;
	private Future<?> _control;

	public JMSConsumer(GlobalContext globalContext, String uri, String workerPool, JMSConnectionData jmsConnectionData, String jndiDestination, String queueName, String topicName, String subscription,
			boolean noLocal, boolean shared, String messageSelector, int workerCount, int minWorkerCount, long pollInterval, String timeUnit, XMLGregorianCalendar at) throws NamingException, JMSException {

		super(uri, workerPool, at, timeUnit, pollInterval, false);
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
		_noLocal = noLocal;
		_shared = shared;
		_jmsWorker = new JMSWorker[workerCount];
		_minWorkerCount = minWorkerCount;
	}

	private String getDestinationName() {
		return _queueName != null ? _queueName : _topicName;
	}

	private Destination getDestination(JMSSession session) throws JMSException {
		if (_destination == null) {
			// All popular MOMs have no session related data in the Destination object, so we dare to cache it
			_destination = _queueName != null ? session.getSession().createQueue(_queueName) : session.getSession().createTopic(_topicName);
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

	@Override
	public String getMBeanPostfix() {
		return ",consumerType=" + getClass().getSimpleName() + ",key=" + ObjectName.quote(getKey());
	}

	public int getWorkerCount() {
		return _workerCount;
	}

	public Date getLastChangeOfState() {
		return new Date(_lastChangeOfState);
	}

	public long getCurrentSentReceiveDelay() {
		return _sentReceiveDelay.getCurrent();
	}

	@Override
	public int compareTo(JMSConsumer o) {
		return getKey().compareTo(o.getKey());
	}

	public synchronized void init(GlobalContext globalContext) throws JMSException {
		initWorkerPool(globalContext);
		// distribute evenly over poll interval
		long delay = _period / _jmsWorker.length;
		for (; _workerCount < _minWorkerCount; ++_workerCount) {
			_jmsWorker[_workerCount] = _period > 0L ? new JMSPollingWorker(delay * _workerCount) : new JMSWorker();
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
			final JMSWorker jmsWorker = _period > 0L ? new JMSPollingWorker(0L) : new JMSWorker();
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

	public Boolean unsubscribe() {
		JMSSession session = _jmsWorker[0]._session;
		if (_subscription != null && session != null) {
			try {
				enable(false);
				session.getSession().unsubscribe(_subscription);
				return true;
			} catch (JMSException e) {
				logger.error("unsubscribe " + _subscription, e);
			}
		}
		return false;
	}

	public static void fillESBMessage(ESBMessage esbMessage, Message message) throws Exception {
		for (@SuppressWarnings("unchecked")
		Enumeration<String> propertyNames = message.getPropertyNames(); propertyNames.hasMoreElements();) {
			String propertyName = propertyNames.nextElement();
			esbMessage.putHeader(propertyName, message.getObjectProperty(propertyName));
		}
		if (message instanceof BytesMessage) {
			esbMessage.reset(BodyType.INPUT_STREAM, new BytesMessageInputStream((BytesMessage) message), message.getStringProperty(ESBConstants.Charset));
			esbMessage.prepareContent();
		} else if (message instanceof TextMessage) {
			TextMessage textMessage = (TextMessage) message;
			esbMessage.reset(BodyType.STRING, textMessage.getText());
			message.clearBody();
			esbMessage.prepareContent();
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
	}

	class JMSWorker implements MessageListener {
		final Context _context = new Context(_workerPool.getPoolContext());
		volatile JMSSession _session;
		volatile MessageConsumer _messageConsumer;

		final void open() throws JMSException {
			JMSSessionFactory jmsSessionFactory = _context.getResourceFactory(JMSSessionFactory.class);
			_session = jmsSessionFactory.getResource(_jmsConnectionData, true);
			_context.putResource(ESBConstants.JMSSession, _session);
		}

		final void initMessageConsumer() throws JMSException {
			if (_messageConsumer == null && _session != null) {
				if (_subscription != null) {
					if (_shared) {
						_messageConsumer = _session.getSession().createSharedDurableConsumer((Topic) getDestination(_session), _subscription, _messageSelector);
					} else {
						_messageConsumer = _session.getSession().createDurableSubscriber((Topic) getDestination(_session), _subscription, _messageSelector, _noLocal);
					}
				} else {
					_messageConsumer = _session.getSession().createConsumer(getDestination(_session), _messageSelector, _noLocal);
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
			esbMessage.putVariable("JMSConnectionData", _jmsConnectionData.toString());
			esbMessage.putVariable(ESBConstants.JMSOrigin, getDestinationName());
			try {
				fillESBMessage(esbMessage, message);
				processInternal(_context, esbMessage);
				_session.getSession().commit();
				long receiveTimestamp = esbMessage.getVariable(ESBConstants.initialTimestamp);
				controlJMSWorkerPool(_sentReceiveDelay.accumulateAndGet(receiveTimestamp - message.getJMSTimestamp()), receiveTimestamp);
				return true;
			} catch (Exception e) {
				logger.info("Rolling back for " + getKey(), e);
				_session.getSession().rollback();
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
				_poller = schedule(this, _initialDelay);
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
				Message message;
				do {
					message = _messageConsumer.receiveNoWait();
				} while (message != null && processMessage(message));
				if (needsReschedule()) {
					_poller = schedule(this, _initialDelay);
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
