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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import javax.jms.Connection;
import javax.jms.ExceptionListener;
import javax.jms.JMSException;
import javax.jms.Session;
import javax.management.AttributeChangeNotification;
import javax.management.MBeanNotificationInfo;
import javax.management.NotificationBroadcasterSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.PoolContext;
import com.artofarc.esb.resource.JMSSessionFactory;
import com.artofarc.esb.resource.ResourceFactory;
import com.artofarc.util.Closer;

public final class JMSConnectionProvider extends ResourceFactory<JMSConnectionProvider.JMSConnectionGuard, JMSConnectionData, Void, RuntimeException> {

	protected final static Logger logger = LoggerFactory.getLogger(JMSConnectionProvider.class);
	protected final static String instanceId = System.getProperty("esb0.jms.instanceId");
	protected final static long closeWithTimeout = Long.parseLong(System.getProperty("esb0.jms.closeWithTimeout", "0"));
	protected final static long reconnectInterval = Long.parseLong(System.getProperty("esb0.jms.reconnectInterval", "60"));

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

	public ExceptionListener getExceptionListener(JMSConnectionData jmsConnectionData) {
		return getResource(jmsConnectionData);
	}

	public void unregisterJMSSessionFactory(JMSConnectionData jmsConnectionData, JMSSessionFactory jmsSessionFactory) {
		getResource(jmsConnectionData).removeJMSSessionFactory(jmsSessionFactory);
	}

	public void registerJMSConsumer(JMSConnectionData jmsConnectionData, JMSConsumer jmsConsumer, boolean enabled) {
		getResource(jmsConnectionData).addJMSConsumer(jmsConsumer, enabled);
	}

	public void unregisterJMSConsumer(JMSConnectionData jmsConnectionData, JMSConsumer jmsConsumer) {
		getResource(jmsConnectionData).removeJMSConsumer(jmsConsumer);
	}

	public JMSSession createSession(JMSConnectionData jmsConnectionData, JMSSessionFactory jmsSessionFactory, boolean transacted) throws JMSException {
		Connection connection = getResource(jmsConnectionData).getConnection(jmsSessionFactory);
		return new JMSSession(this, jmsConnectionData, connection.createSession(transacted, transacted ? Session.SESSION_TRANSACTED : Session.AUTO_ACKNOWLEDGE));
	}

	public boolean isConnected(JMSConnectionData jmsConnectionData) {
		return getResource(jmsConnectionData).isConnected();
	}

	void closeSession(JMSConnectionData jmsConnectionData, JMSSession jmsSession) throws JMSException {
		if (closeWithTimeout > 0) {
			// Oracle AQ sometimes waits forever in close()
			Closer.closeWithTimeout(jmsSession.getSession(), _poolContext.getWorkerPool().getExecutorService(), closeWithTimeout, jmsConnectionData.toString(), JMSException.class);
		} else {
			jmsSession.getSession().close();
		}
	}

	final class JMSConnectionGuard extends NotificationBroadcasterSupport implements AutoCloseable, ExceptionListener, Runnable, GlobalContext.PropertyChangeListener, com.artofarc.esb.mbean.JMSConnectionGuardMXBean {

		private final ReentrantLock _lock = new ReentrantLock();
		private final Map<JMSConsumer, Boolean> _jmsConsumers = new ConcurrentHashMap<>();
		private final Set<JMSSessionFactory> _jmsSessionFactories = ConcurrentHashMap.newKeySet();
		private final JMSConnectionData _jmsConnectionData;
		private final String _clientID; 
		private volatile Connection _connection;
		private volatile Future<?> _future;
		private volatile long _sequenceNumber;

		private JMSConnectionGuard(JMSConnectionData jmsConnectionData) {
			super(_poolContext.getWorkerPool().getExecutorService(), new MBeanNotificationInfo(new String[] { AttributeChangeNotification.ATTRIBUTE_CHANGE },
					AttributeChangeNotification.class.getName(), "A JMS Connection of " + jmsConnectionData + " changes its state"));
			_jmsConnectionData = jmsConnectionData;
			_clientID = instanceId != null ? instanceId + "-" + jmsConnectionData + "-" + _poolContext.getWorkerPool().getName() : null;
		}

		void addJMSConsumer(JMSConsumer jmsConsumer, boolean enabled) {
			_jmsConsumers.put(jmsConsumer, enabled);
		}

		void removeJMSConsumer(JMSConsumer jmsConsumer) {
			_jmsConsumers.remove(jmsConsumer);
		}

		private Connection createConnection() throws JMSException {
			Connection connection = _jmsConnectionData.createConnection(_poolContext.getGlobalContext(), this);
			sendNotification(new AttributeChangeNotification(this, ++_sequenceNumber, System.currentTimeMillis(), "Connection state changed", "connected", "boolean", false, true));
			try {
				if (_clientID != null) {
					connection.setClientID(_clientID);
				}
				connection.start();
			} catch (JMSException e) {
				closeConnection(connection);
				throw e;
			}
			return connection;
		}

		Connection getConnection(JMSSessionFactory jmsSessionFactory) throws JMSException {
			Connection connection = _connection;
			if (connection == null && _future == null) {
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
					startReconnectThread();
					throw e;
				} finally {
					_lock.unlock();
				}
			}
			if (connection == null) {
				throw new JMSException("Currently reconnecting " + _jmsConnectionData);
			}
			_jmsSessionFactories.add(jmsSessionFactory);
			return connection;
		}

		void removeJMSSessionFactory(JMSSessionFactory jmsSessionFactory) {
			_jmsSessionFactories.remove(jmsSessionFactory);
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
			sendNotification(new AttributeChangeNotification(this, ++_sequenceNumber, System.currentTimeMillis(), "Connection state changed", "connected", "boolean", true, false));
		}

		private void startReconnectThread() {
			logger.info("Start reconnect thread for " + _jmsConnectionData);
			ScheduledExecutorService scheduledExecutorService = _poolContext.getWorkerPool().getScheduledExecutorService();
			if (scheduledExecutorService == null) {
				scheduledExecutorService = _poolContext.getGlobalContext().getDefaultWorkerPool().getScheduledExecutorService();
			}
			_future = scheduledExecutorService.scheduleAtFixedRate(this, reconnectInterval, reconnectInterval, TimeUnit.SECONDS);
		}

		@Override
		public void onException(JMSException jmsException) {
			logger.warn("JMSException received for " + _jmsConnectionData, jmsException);
			reconnect();
		}

		@Override
		public void propertyChange(String key, Object oldValue, Object newValue) {
			if (_connection != null) {
				_lock.lock();
				try {
					Connection connection = _connection;
					if (connection != null) {
						_connection = null;
						shutdown(connection);
						run();
					}
				} finally {
					_lock.unlock();
				}
			}
		}

		public void reconnect() {
			if (_connection != null) {
				_lock.lock();
				try {
					Connection connection = _connection;
					if (connection != null) {
						_connection = null;
						_future = _poolContext.getWorkerPool().getExecutorService().submit(() -> {
							shutdown(connection);
							startReconnectThread();
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
				try {
					logger.info("Suspending JMSConsumer for " + jmsConsumer.getKey() + " in state " + entry.getValue());
					jmsConsumer.suspend();
				} catch (Exception e) {
					logger.warn("Suspend failed for " + jmsConsumer.getKey(), e);
				}
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
			logger.info("Trying to reconnect " + _jmsConnectionData);
			try {
				_connection = createConnection();
				for (Map.Entry<JMSConsumer, Boolean> entry : _jmsConsumers.entrySet()) {
					JMSConsumer jmsConsumer = entry.getKey();
					jmsConsumer.resume();
					// restore last state
					jmsConsumer.enable(entry.getValue());
					logger.info("Resumed " + jmsConsumer.getKey() + " to state " + entry.getValue());
				}
				_connection.setExceptionListener(this);
				logger.info("Reconnected " + _jmsConnectionData);
				if (_future != null) {
					_future.cancel(false);
					_future = null;
				}
			} catch (JMSException e) {
				logger.error("Reconnect failed for " + _jmsConnectionData, e);
				if (_connection != null) {
					shutdown(_connection);
					_connection = null;
				}
			}
		}

		public boolean isConnected() {
			return _connection != null;
		}

		@Override
		public void close() throws JMSException {
			_poolContext.getGlobalContext().unregisterMBean(getObjectName(_jmsConnectionData));
			if (_future != null) {
				_future.cancel(false);
			}
			if (_connection != null) {
				_connection.close();
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
	}

}
