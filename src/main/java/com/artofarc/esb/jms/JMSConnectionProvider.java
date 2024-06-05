/*
 * Copyright 2021 Andre Karalus
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
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionMetaData;
import jakarta.jms.ExceptionListener;
import jakarta.jms.JMSException;
import javax.management.AttributeChangeNotification;
import javax.management.MBeanNotificationInfo;
import javax.management.NotificationBroadcasterSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.PoolContext;
import com.artofarc.esb.context.WorkerPool;
import com.artofarc.esb.resource.JMSSessionFactory;
import com.artofarc.esb.resource.ResourceFactory;
import com.artofarc.util.Closer;
import com.artofarc.util.DataStructures;

public final class JMSConnectionProvider extends ResourceFactory<JMSConnectionProvider.JMSConnectionGuard, JMSConnectionData, Void, RuntimeException> {

	final static Logger logger = LoggerFactory.getLogger(JMSConnectionProvider.class);
	final static long closeWithTimeout = Long.parseLong(System.getProperty("esb0.jms.closeWithTimeout", "0"));
	final static long reconnectInterval = Long.parseLong(System.getProperty("esb0.jms.reconnectInterval", "60"));

	private final PoolContext _poolContext;

	public JMSConnectionProvider(PoolContext poolContext) {
		_poolContext = poolContext;
	}

	private String getObjectName(JMSConnectionData jmsConnectionData) {
		return ",group=JMSConnectionGuard,name=\"" + jmsConnectionData + "\",WorkerPool=" + _poolContext.getWorkerPool().getName();
	}

	@Override
	protected JMSConnectionGuard createResource(JMSConnectionData jmsConnectionData, Void param) {
		JMSConnectionGuard connectionGuard = new JMSConnectionGuard(jmsConnectionData);
		_poolContext.getGlobalContext().registerMBean(connectionGuard, getObjectName(jmsConnectionData));
		return connectionGuard;
	}

	ExceptionListener getExceptionListener(JMSConnectionData jmsConnectionData) {
		return getResource(jmsConnectionData);
	}

	public void unregisterJMSSessionFactory(JMSConnectionData jmsConnectionData, JMSSessionFactory jmsSessionFactory) {
		getResource(jmsConnectionData).removeJMSSessionFactory(jmsSessionFactory);
	}

	void registerJMSConsumer(JMSConnectionData jmsConnectionData, JMSConsumer jmsConsumer, boolean enabled) {
		getResource(jmsConnectionData).addJMSConsumer(jmsConsumer, enabled);
	}

	void unregisterJMSConsumer(JMSConnectionData jmsConnectionData, JMSConsumer jmsConsumer) {
		getResource(jmsConnectionData).removeJMSConsumer(jmsConsumer);
	}

	final class JMSConnectionGuard extends NotificationBroadcasterSupport implements AutoCloseable, ExceptionListener, Runnable, GlobalContext.PropertyChangeListener, com.artofarc.esb.mbean.JMSConnectionGuardMXBean {

		private final ReentrantLock _lock = new ReentrantLock();
		private final Map<JMSConsumer, Boolean> _jmsConsumers = new ConcurrentHashMap<>();
		private final Set<JMSSessionFactory> _jmsSessionFactories = ConcurrentHashMap.newKeySet();
		private final JMSConnectionData _jmsConnectionData;
		private volatile Connection _connection;
		private volatile ConnectionMetaData _connectionMetaData;
		private volatile Future<?> _future;
		private volatile long _sequenceNumber;
		private volatile long _lastChangeOfState;
		private volatile boolean _disconnected;
		private final AtomicBoolean _connecting = new AtomicBoolean();

		private JMSConnectionGuard(JMSConnectionData jmsConnectionData) {
			super(_poolContext.getWorkerPool().getExecutorService(), new MBeanNotificationInfo(new String[] { AttributeChangeNotification.ATTRIBUTE_CHANGE },
					AttributeChangeNotification.class.getName(), "A JMS Connection of " + jmsConnectionData + " changes its state"));
			_jmsConnectionData = jmsConnectionData;
		}

		void addJMSConsumer(JMSConsumer jmsConsumer, boolean enabled) {
			_jmsConsumers.put(jmsConsumer, enabled);
		}

		void removeJMSConsumer(JMSConsumer jmsConsumer) {
			_jmsConsumers.remove(jmsConsumer);
			checkClose();
		}

		private Connection createConnection() throws JMSException {
			Connection connection = _jmsConnectionData.createConnection(_poolContext.getGlobalContext(), this);
			sendNotification(new AttributeChangeNotification(this, ++_sequenceNumber, _lastChangeOfState = System.currentTimeMillis(), "Connection state changed", "connected", "boolean", false, true));
			try {
				_connectionMetaData = connection.getMetaData();
				connection.start();
			} catch (JMSException e) {
				closeConnection(connection);
				throw e;
			}
			return connection;
		}

		ConnectionMetaData getConnectionMetaData() {
			return _connectionMetaData;
		}

		Connection getConnection() throws JMSException {
			Connection connection = _connection;
			if (connection == null && _future == null && !_disconnected) {
				_lock.lock();
				try {
					connection = _connection;
					if (connection == null && _future == null) {
						connection = createConnection();
						connection.setExceptionListener(this);
						_connection = connection;
					}
				} catch (JMSException e) {
					logger.error("Currently cannot connect using " + _jmsConnectionData, e);
					scheduleReconnectTask();
					throw e;
				} finally {
					_lock.unlock();
				}
			}
			if (connection == null) {
				throw new JMSException("Currently not connected to " + _jmsConnectionData);
			}
			return connection;
		}

		void closeSession(JMSSession jmsSession) throws JMSException {
			if (closeWithTimeout > 0) {
				// Oracle AQ sometimes waits forever in close()
				Closer.closeWithTimeout(jmsSession.getSession(), _poolContext.getWorkerPool().getExecutorService(), closeWithTimeout, getConnectionData(), JMSException.class);
			} else {
				jmsSession.getSession().close();
			}
		}

		void addJMSSessionFactory(JMSSessionFactory jmsSessionFactory) {
			_jmsSessionFactories.add(jmsSessionFactory);
		}

		void removeJMSSessionFactory(JMSSessionFactory jmsSessionFactory) {
			_jmsSessionFactories.remove(jmsSessionFactory);
			checkClose();
		}

		private void closeConnection(Connection connection) {
			logger.info("Closing Connection for " + _jmsConnectionData);
			try {
				if (closeWithTimeout > 0) {
					// Oracle AQ sometimes waits forever in close()
					Closer.closeWithTimeout(connection, _poolContext.getWorkerPool().getExecutorService(), closeWithTimeout, _jmsConnectionData.toString(), JMSException.class);
				} else {
					connection.close();
				}
			} catch (JMSException e) {
				// ignore
			}
			sendNotification(new AttributeChangeNotification(this, ++_sequenceNumber, _lastChangeOfState = System.currentTimeMillis(), "Connection state changed", "connected", "boolean", true, false));
		}

		private void scheduleReconnectTask() {
			logger.info("Start reconnect thread for " + _jmsConnectionData);
			ScheduledExecutorService scheduledExecutorService = _poolContext.getWorkerPool().getScheduledExecutorService();
			if (scheduledExecutorService == null) {
				scheduledExecutorService = _poolContext.getGlobalContext().getDefaultWorkerPool().getScheduledExecutorService();
			}
			_future = scheduledExecutorService.scheduleAtFixedRate(this, reconnectInterval, reconnectInterval, TimeUnit.SECONDS);
		}

		private void cancelReconnectTask() {
			Future<?> future = _future;
			if (future != null && future.cancel(false)) {
				_future = null;
			}
		}

		@Override
		public void onException(JMSException jmsException) {
			logger.warn("JMSException received for " + _jmsConnectionData, jmsException);
			scheduleReconnect();
		}

		@Override
		public void propertyChange(String key, Object oldValue, Object newValue) {
			disconnect();
			connect();
		}

		public void scheduleReconnect() {
			if (_future == null) {
				_lock.lock();
				try {
					if (_future == null) {
						Connection connection = _connection;
						_connection = null;
						WorkerPool workerPool = _poolContext.getWorkerPool();
						if (workerPool.getGuaranteedPoolSize() < 2) {
							workerPool = _poolContext.getGlobalContext().getDefaultWorkerPool();
						}
						_future = workerPool.getExecutorService().submit(() -> {
							if (connection != null) {
								shutdown(connection);
							}
							scheduleReconnectTask();
						});
					}
				} finally {
					_lock.unlock();
				}
			}
		}

		private void shutdown(Connection connection) {
			logger.info("Connection will be closed for " + _jmsConnectionData);
			try {
				connection.setExceptionListener(null);
				connection.stop();
			} catch (JMSException e) {
				// ignore
			}
			for (Map.Entry<JMSConsumer, Boolean> entry : _jmsConsumers.entrySet()) {
				JMSConsumer jmsConsumer = entry.getKey();
				// save current state
				entry.setValue(jmsConsumer.isToBeEnabled());
				logger.info("Suspending JMSConsumer for " + jmsConsumer.getKey() + " in state " + entry.getValue());
				jmsConsumer.suspend();
			}
			for (JMSSessionFactory jmsSessionFactory : _jmsSessionFactories) {
				try {
					logger.info("Closing JMSSessionFactory");
					jmsSessionFactory.close(_jmsConnectionData);
				} catch (Exception e) {
					// ignore
				}
			}
			closeConnection(connection);
		}

		@Override
		public void run() {
			connect();
		}

		public boolean isConnected() {
			return _connection != null;
		}

		public void connect() {
			if (_connecting.compareAndSet(false, true)) {
				try {
					if (_connection == null) {
						logger.info("Trying to connect " + _jmsConnectionData);
						_connection = createConnection();
						for (Map.Entry<JMSConsumer, Boolean> entry : _jmsConsumers.entrySet()) {
							JMSConsumer jmsConsumer = entry.getKey();
							jmsConsumer.resume();
							// restore last state
							jmsConsumer.enable(entry.getValue());
							logger.info("Resumed " + jmsConsumer.getKey() + " to state " + entry.getValue());
						}
						_connection.setExceptionListener(this);
						logger.info("Connected " + _jmsConnectionData);
						cancelReconnectTask();
					}
					_disconnected = false;
				} catch (JMSException e) {
					logger.error("Connect failed for " + _jmsConnectionData, e);
					if (_connection != null) {
						shutdown(_connection);
						_connection = null;
					}
				} finally {
					_connecting.set(false);
				}
			}
		}

		public void disconnect() {
			_disconnected = true;
			if (_connection != null) {
				_lock.lock();
				try {
					Connection connection = _connection;
					if (connection != null) {
						_connection = null;
						shutdown(connection);
					}
				} finally {
					_lock.unlock();
				}
			}
		}

		@Override
		public void close() throws JMSException {
			_poolContext.getGlobalContext().unregisterMBean(getObjectName(_jmsConnectionData));
			cancelReconnectTask();
			if (_connection != null) {
				_connection.close();
			}
		}

		private void checkClose() {
			if (_jmsConsumers.isEmpty() && _jmsSessionFactories.isEmpty()) {
				removeResource(_jmsConnectionData);
				Closer.closeQuietly(this);
			}
		}

		public List<String> getProducerDestinationCount() throws JMSException {
			Map<String, Integer> map = new HashMap<>();
			for (JMSSessionFactory jmsSessionFactory : _jmsSessionFactories) {
				for (JMSSession jmsSession : jmsSessionFactory.getResources()) {
					for (String destination : jmsSession.getProducerDestinations()) {
						Integer count = map.get(destination);
						if (count == null) {
							map.put(destination, 1);
						} else {
							map.put(destination, count + 1);
						}
					}
				}
			}
			List<String> result = new ArrayList<>();
			for (Map.Entry<String, Integer> entry : map.entrySet()) {
				result.add(entry.getKey() + "=" + entry.getValue());
			}
			return result;
		}

		public List<String> getConsumerDestinationCount() {
			List<String> result = DataStructures.createList(_jmsConsumers.size());
			for (JMSConsumer jmsConsumer : _jmsConsumers.keySet()) {
				result.add(jmsConsumer.getKey() + "=" + jmsConsumer.getWorkerCount());
			}
			return result;
		}

		public String getConnectionData() {
			return _jmsConnectionData.toString();
		}

		public String getJMSProvider() throws JMSException {
			ConnectionMetaData connectionMetaData = _connectionMetaData;
			return connectionMetaData != null ? connectionMetaData.getJMSProviderName() + ' ' + connectionMetaData.getClass().getPackage().getImplementationVersion() : null;
		}

		public Date getLastChangeOfState() {
			return new Date(_lastChangeOfState);
		}
	}

}
