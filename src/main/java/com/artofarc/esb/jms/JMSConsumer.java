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

import java.sql.SQLException;
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
import javax.xml.namespace.QName;

import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.Registry;
import com.artofarc.esb.SchedulingConsumerPort;
import com.artofarc.esb.Trend;
import com.artofarc.esb.action.JDBCAction;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.jdbc.JDBC2XMLMapper;
import com.artofarc.esb.jms.aq.AdtHelper;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.resource.JMSSessionFactory;
import com.artofarc.util.Closer;
import com.sun.xml.xsom.XSSchemaSet;

public final class JMSConsumer extends SchedulingConsumerPort implements com.artofarc.esb.mbean.JMSConsumerMXBean {

	private final JMSConnectionData _jmsConnectionData;
	private Destination _destination;
	private final String _queueName;
	private final String _topicName;
	private final JDBC2XMLMapper _mapper;
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
	private final int _batchSize;
	private final long _batchTime;
	private long _lastControlChange;
	private volatile boolean _operating;
	private volatile int _workerCount;
	private final Trend _sentReceiveDelay = new Trend(10L);
	private volatile long _lastChangeOfState;
	private Future<?> _control;

	public JMSConsumer(GlobalContext globalContext, String uri, String workerPool, JMSConnectionData jmsConnectionData, String jndiDestination, String queueName, String topicName,
			XSSchemaSet schemaSet, String rootElement, String subscription, boolean noLocal, boolean shared, String messageSelector, int workerCount, int minWorkerCount, int batchSize,
			int batchTime, long pollInterval, String timeUnit, XMLGregorianCalendar at) throws NamingException, JMSException {

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
			_queueName = globalContext.bindProperties(queueName);
			_topicName = globalContext.bindProperties(topicName);
		}
		_mapper = schemaSet != null && rootElement != null ? new JDBC2XMLMapper(schemaSet, QName.valueOf(rootElement)) : null;
		if (subscription != null) {
			if (_topicName == null) throw new IllegalArgumentException("Subscription only allowed for topics: " + getKey());
			if (!shared && workerCount != 1) throw new IllegalArgumentException("Subscriptions can only have one worker: " + getKey());
			if (shared && _mapper != null) throw new IllegalArgumentException("Oracle AQ does not support shared subscriptions: " + getKey());
		}
		_subscription = subscription;
		_noLocal = noLocal;
		_shared = shared;
		_jmsWorker = new JMSWorker[workerCount];
		_minWorkerCount = minWorkerCount;
		_batchSize = batchSize;
		_batchTime = batchTime * 1000000;
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
		return super.getMBeanPostfix() + ",key=" + ObjectName.quote(getKey());
	}

	public int getMaxWorkerCount() {
		return _jmsWorker.length;
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

	public synchronized void init(GlobalContext globalContext) throws JMSException {
		initWorkerPool(globalContext);
		if (_workerPool.getExecutorService() == null) {
			throw new IllegalArgumentException("WorkerPool must have an ExecutorService: " + _workerPoolName);
		}
		// distribute evenly spread over poll interval
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

	public void adjustJMSWorkerPool() {
		adjustJMSWorkerPool(0, System.currentTimeMillis());
	}

	private synchronized void adjustJMSWorkerPool(long sentReceiveDelay, long receiveTimestamp) {
		boolean notBusy = _control == null || _control.isDone();
		if (_operating && _workerCount < _jmsWorker.length && sentReceiveDelay > _rampUpThreshold && notBusy && _lastControlChange + _rampUpDelay < receiveTimestamp) {
			// add Worker
			final JMSWorker jmsWorker = _period > 0L ? new JMSPollingWorker(0L) : new JMSWorker();
			_control = _workerPool.getExecutorService().submit(() -> {
				try {
					jmsWorker.open();
					jmsWorker.startListening();
				} catch (JMSException e) {
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
				jmsWorker.close();
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

	void suspend() {
		try {
			enable(false);
		} catch (JMSException e) {
			// ignore
		}
		for (int i = 0; i < _workerCount;) {
			JMSWorker jmsWorker = _jmsWorker[i++];
			jmsWorker.close();
		}
	}

	@Override
	public void close() {
		for (int i = 0; i < _workerCount;) {
			JMSWorker jmsWorker = _jmsWorker[i++];
			jmsWorker.close();
			jmsWorker._context.close();
		}
		_workerCount = 0;
		JMSConnectionProvider jmsConnectionProvider = _workerPool.getPoolContext().getResourceFactory(JMSConnectionProvider.class);
		jmsConnectionProvider.unregisterJMSConsumer(_jmsConnectionData, this);
	}

	public boolean unsubscribe() {
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

	public static void fillESBMessage(Context context, ESBMessage esbMessage, Message message) throws Exception {
		for (@SuppressWarnings("unchecked")
		Enumeration<String> propertyNames = message.getPropertyNames(); propertyNames.hasMoreElements();) {
			String propertyName = propertyNames.nextElement();
			esbMessage.putHeader(propertyName, message.getObjectProperty(propertyName));
		}
		if (message instanceof BytesMessage) {
			esbMessage.reset(BodyType.INPUT_STREAM, new BytesMessageInputStream((BytesMessage) message), message.getStringProperty(ESBConstants.Charset));
			esbMessage.prepareContent(context);
		} else if (message instanceof TextMessage) {
			TextMessage textMessage = (TextMessage) message;
			esbMessage.reset(BodyType.STRING, textMessage.getText());
			message.clearBody();
			esbMessage.prepareContent(context);
		}
		esbMessage.putVariable(ESBConstants.JMSMessageID, message.getJMSMessageID());
		esbMessage.putVariable(ESBConstants.JMSTimestamp, message.getJMSTimestamp());
		esbMessage.putVariable(ESBConstants.JMSExpiration, message.getJMSExpiration());
		esbMessage.putVariableIfNotNull(ESBConstants.JMSCorrelationID, message.getJMSCorrelationID());
		try {
			esbMessage.putVariableIfNotNull(ESBConstants.JMSType, message.getJMSType());
			esbMessage.putVariableIfNotNull(ESBConstants.JMSReplyTo, message.getJMSReplyTo());
		} catch (JMSException e) {
			// Oracle AdtMessage does not support these features
		}
		Map.Entry<String, String> destinationName = JMSSession.getDestinationName(message.getJMSDestination());
		if (destinationName != null) {
			esbMessage.putVariable(destinationName.getKey(), destinationName.getValue());
		}
	}

	class JMSWorker implements MessageListener {
		final Context _context = new Context(_workerPool.getPoolContext());
		volatile JMSSession _session;
		MessageConsumer _messageConsumer;

		final void open() throws JMSException {
			JMSSessionFactory jmsSessionFactory = _context.getResourceFactory(JMSSessionFactory.class);
			_session = jmsSessionFactory.getResource(_jmsConnectionData, true);
			_context.putResource(ESBConstants.JMSSession, _session);
		}

		protected final void initMessageConsumer() throws JMSException {
			if (_messageConsumer == null && _session != null) {
				if (_subscription == null) {
					if (_mapper != null) {
						_messageConsumer = AdtHelper.createAdtConsumer(_session.getSession(), getDestination(_session), _messageSelector, _noLocal);
					} else {
						_messageConsumer = _session.getSession().createConsumer(getDestination(_session), _messageSelector, _noLocal);
					}
				} else if (_shared) {
					_messageConsumer = _session.getSession().createSharedDurableConsumer((Topic) getDestination(_session), _subscription, _messageSelector);
				} else if (_mapper != null) {
					_messageConsumer = AdtHelper.createAdtDurableSubscriber(_session.getSession(), (Topic) getDestination(_session), _subscription, _messageSelector, _noLocal);
				} else {
					_messageConsumer = _session.getSession().createDurableSubscriber((Topic) getDestination(_session), _subscription, _messageSelector, _noLocal);
				}
			}
		}

		synchronized void startListening() throws JMSException {
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

		synchronized void stopListening() {
			if (_messageConsumer != null) {
				try {
					if (JMSConnectionProvider.closeWithTimeout > 0) {
						// Oracle AQ sometimes waits forever in close()
						Closer.closeWithTimeout(_messageConsumer, _workerPool.getExecutorService(), JMSConnectionProvider.closeWithTimeout, getKey(), JMSException.class);
					} else {
						_messageConsumer.close();
					}
				} catch (JMSException e) {
					// ignore
					logger.debug(getKey(), e);
				} finally {
					_messageConsumer = null;
				}
			}
		}

		final void close() {
			stopListening();
			_session = null;
			JMSConnectionProvider jmsConnectionProvider = _context.getPoolContext().getResourceFactory(JMSConnectionProvider.class);
			JMSSessionFactory jmsSessionFactory = _context.getResourceFactory(JMSSessionFactory.class);
			jmsConnectionProvider.unregisterJMSSessionFactory(_jmsConnectionData, jmsSessionFactory);
			try {
				jmsSessionFactory.close(_jmsConnectionData);
			} catch (Exception e) {
				logger.debug("Could not close JMSSession", e);
			}
		}

		@Override
		public void onMessage(Message message) {
			// monitor threads not started by us but by the JMS provider
			_workerPool.addThread(Thread.currentThread(), getDestinationName());
			try {
				long receiveTimestamp = processMessage(message);
				commit(receiveTimestamp, receiveTimestamp - message.getJMSTimestamp());
			} catch (Exception e) {
				logger.info("Rolling back for " + getKey(), e);
				try {
					rollback();
				} catch (JMSException je) {
					throw new RuntimeException(je);
				}
			}
		}

		protected final long processMessage(Message message) throws Exception {
			ESBMessage esbMessage = new ESBMessage(BodyType.INVALID, null);
			esbMessage.putVariable("JMSConnectionData", _jmsConnectionData.toString());
			esbMessage.putVariable(ESBConstants.JMSOrigin, getDestinationName());
			fillESBMessage(_context, esbMessage, message);
			if (_mapper != null) {
				AdtHelper.parseAdtMessage(message, _mapper, _context, esbMessage);
			}
			processInternal(_context, esbMessage);
			return esbMessage.getVariable(ESBConstants.initialTimestamp);
		}

		protected final void commit(long receiveTimestamp, long sentReceiveDelay) throws JMSException {
			try {
				JDBCAction.closeKeptConnections(_context, true);
				JMSSessionFactory jmsSessionFactory = _context.getResourceFactory(JMSSessionFactory.class);
				jmsSessionFactory.commitTransactedJMSSessions(_session);
			} catch (SQLException | JMSException e) {
				logger.info("Rolling back for " + getKey(), e);
				rollback();
				return;
			}
			_session.getSession().commit();
			adjustJMSWorkerPool(_sentReceiveDelay.accumulateAndGet(sentReceiveDelay), receiveTimestamp);
		}

		protected final void rollback() throws JMSException {
			try {
				JDBCAction.closeKeptConnections(_context, false);
			} catch (SQLException e) {
				// ignore
			}
			JMSSessionFactory jmsSessionFactory = _context.getResourceFactory(JMSSessionFactory.class);
			jmsSessionFactory.rollbackTransactedJMSSessions(_session);
			_session.getSession().rollback();
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
			if (_poller == null) {
				synchronized (this) {
					if (_poller == null) {
						initMessageConsumer();
						if (_messageConsumer != null) {
							_poller = schedule(this, _initialDelay);
						}
					}
				}
			}
		}

		void stopListening() {
			Future<?> poller = _poller;
			if (poller != null) {
				poller.cancel(false);
				_poller = null;
				synchronized (this) {
					super.stopListening();
					if (needsReschedule() && (poller = _poller) != null) {
						poller.cancel(false);
						_poller = null;
					}
				}
			}
		}

		@Override
		public synchronized void run() {
			try {
				long receiveTimestamp = 0, sentReceiveDelay = 0, start = System.nanoTime();
				int i = 0;
				Message message;
				while (_poller != null && (message = _messageConsumer.receiveNoWait()) != null) {
					try {
						receiveTimestamp = processMessage(message);
						sentReceiveDelay = receiveTimestamp - message.getJMSTimestamp();
					} catch (Exception e) {
						logger.info("Rolling back for " + getKey(), e);
						rollback();
						i = 0;
						break;
					}
					if (++i == _batchSize || System.nanoTime() - start > _batchTime) {
						commit(receiveTimestamp, sentReceiveDelay);
						if (i < _batchSize) {
							logger.info("Batch timeout reached. Processed messages: " + i);
						}
						i = 0;
						start = System.nanoTime();
					}
				}
				if (i > 0) {
					commit(receiveTimestamp, sentReceiveDelay);
				}
				if (needsReschedule() && _poller != null) {
					_poller = schedule(this, _initialDelay);
				}
			} catch (JMSException e) {
				JMSConnectionProvider jmsConnectionProvider = _context.getPoolContext().getResourceFactory(JMSConnectionProvider.class);
				jmsConnectionProvider.getExceptionListener(_jmsConnectionData).onException(e);
			}
		}
	}

	@Override
	public void bind(Registry registry) {
		registry.registerMBean(this, getMBeanPostfix());
		registry.registerJmsConsumer(this);
	}

	@Override
	public void unbind(Registry registry) {
		registry.unbindJmsConsumer(this);
	}

	@Override
	public boolean needsSyncClose(ConsumerPort oldConsumerPort) {
		if (oldConsumerPort instanceof JMSConsumer) {
			return getKey().equals(((JMSConsumer) oldConsumerPort).getKey());
		}
		if (oldConsumerPort instanceof JMSConsumerGroup) {
			for (JMSConsumer jmsConsumer : ((JMSConsumerGroup) oldConsumerPort).getGroup()) {
				if (getKey().equals(jmsConsumer.getKey())) return true;
			}
		}
		return false;
	}

}
