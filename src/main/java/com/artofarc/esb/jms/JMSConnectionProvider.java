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
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.jms.Connection;
import javax.jms.ExceptionListener;
import javax.jms.JMSException;
import javax.jms.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.artofarc.esb.context.PoolContext;
import com.artofarc.esb.resource.JMSSessionFactory;
import com.artofarc.util.Closer;

public final class JMSConnectionProvider {

	protected final static Logger logger = LoggerFactory.getLogger(JMSConnectionProvider.class);
	protected final static String instanceId = System.getProperty("esb0.jms.instanceId");
	protected final static long closeWithTimeout = Long.parseLong(System.getProperty("esb0.jms.closeWithTimeout", "0"));
	protected final static long reconnectInterval = Long.parseLong(System.getProperty("esb0.jms.reconnectInterval", "60"));
	
	private final HashMap<JMSConnectionData, JMSConnectionGuard> _pool = new HashMap<>();
	private final PoolContext _poolContext;
	private final HashSet<JMSSessionFactory> _jmsSessionFactories = new HashSet<>();

	public JMSConnectionProvider(PoolContext poolContext) {
		_poolContext = poolContext;
	}

	public Set<String> getJMSSessionFactories() {
		Set<String> result = new HashSet<>();
		for (JMSConnectionData jmsConnectionData : _pool.keySet()) {
			result.add(jmsConnectionData.toString());
		}
		return result;
	}

	public synchronized ExceptionListener getExceptionListener(JMSConnectionData jmsConnectionData) {
		return getJMSConnectionGuard(jmsConnectionData);
	}

	private JMSConnectionGuard getJMSConnectionGuard(JMSConnectionData jmsConnectionData) {
		JMSConnectionGuard connectionGuard = _pool.get(jmsConnectionData);
		if (connectionGuard == null) {
			connectionGuard = new JMSConnectionGuard(jmsConnectionData);
			_pool.put(jmsConnectionData, connectionGuard);
			_poolContext.getGlobalContext().registerMBean(connectionGuard, getObjectName(jmsConnectionData.toString()));
		}
		return connectionGuard;
	}

	private String getObjectName(String jndiConnectionFactory) {
		return ",group=JMSConnectionGuard,name=\"" + jndiConnectionFactory + "\",WorkerPool=" + _poolContext.getWorkerPool().getName();
	}

	public synchronized Connection getConnection(JMSConnectionData jmsConnectionData) throws JMSException {
		return getJMSConnectionGuard(jmsConnectionData).getConnection();
	}

	public synchronized void registerJMSConsumer(JMSConnectionData jmsConnectionData, JMSConsumer jmsConsumer, boolean enabled) {
		JMSConnectionGuard connectionGuard = getJMSConnectionGuard(jmsConnectionData);
		connectionGuard.addJMSConsumer(jmsConsumer, enabled);
	}

	public synchronized void registerJMSSessionFactory(JMSSessionFactory jmsSessionFactory) {
		_jmsSessionFactories.add(jmsSessionFactory);
	}

	public synchronized void close() {
		for (Entry<JMSConnectionData, JMSConnectionGuard> entry : _pool.entrySet()) {
			try {
				_poolContext.getGlobalContext().unregisterMBean(getObjectName(entry.getKey().toString()));
				entry.getValue().getConnection().close();
			} catch (JMSException e) {
				// ignore
			}
		}
		_pool.clear();
	}

	void closeSession(JMSConnectionData jmsConnectionData, Session session) throws Exception {
		if (closeWithTimeout > 0) {
			Closer closer = new Closer(_poolContext.getWorkerPool().getExecutorService());
			// Oracle AQ sometimes waits forever in close()
			closer.closeWithTimeout(session, closeWithTimeout, jmsConnectionData.toString());
		} else {
			session.close();
		}
	}

	final class JMSConnectionGuard extends TimerTask implements ExceptionListener, com.artofarc.esb.mbean.JMSConnectionGuardMXBean {

		private final HashMap<JMSConsumer, Boolean> _jmsConsumers = new HashMap<>();
		private final JMSConnectionData _jmsConnectionData;
		private final String _clientID; 
		private volatile Connection _connection;
		private ScheduledFuture<?> _future;

		private JMSConnectionGuard(JMSConnectionData jmsConnectionData) {
			_jmsConnectionData = jmsConnectionData;
			_clientID = instanceId != null ? instanceId + _poolContext.getWorkerPool().getName() : null;
		}

		synchronized void addJMSConsumer(JMSConsumer jmsConsumer, boolean enabled) {
			_jmsConsumers.put(jmsConsumer, enabled);
		}

		private void createConnection() throws JMSException {
			_connection = _jmsConnectionData.createConnection();
			try {
				if (_clientID != null) {
					_connection.setClientID(_clientID);
				}
				_connection.start();
			} catch (JMSException e) {
				closeConnection();
				throw e;
			}
		}

		Connection getConnection() throws JMSException {
			if (_connection == null) {
				if (_future == null) {
					try {
						createConnection();
					} catch (JMSException e) {
						logger.error("Currently cannot connect using " + _jmsConnectionData, e);
						startReconnectThread();
						throw e;
					}
					_connection.setExceptionListener(this);
				} else {
					throw new JMSException("Currently cannot connect using " + _jmsConnectionData);
				}
			}
			return _connection;
		}

		private void closeConnection() {
			logger.info("Closing Connection for " + _jmsConnectionData);
			try {
				if (closeWithTimeout > 0) {
					Closer closer = new Closer(_poolContext.getWorkerPool().getExecutorService());
					// Oracle AQ sometimes waits forever in close()
					closer.closeWithTimeout(_connection, closeWithTimeout, _jmsConnectionData.toString());
				} else {
					_connection.close();
				}
			} catch (Exception e) {
				// ignore
			}
			_connection = null;
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
		public synchronized void onException(JMSException jmsException) {
			if (_connection != null) {
				logger.warn("Connection will be closed for " + _jmsConnectionData, jmsException);
				try {
					_connection.setExceptionListener(null);
					_connection.stop();
				} catch (JMSException e) {
					// ignore
				}
				for (Entry<JMSConsumer, Boolean> entry : _jmsConsumers.entrySet()) {
					JMSConsumer jmsConsumer = entry.getKey();
					// save current state
					entry.setValue(jmsConsumer.isEnabled());
					try {
						logger.info("Suspending JMSConsumer for " + jmsConsumer.getKey());
						jmsConsumer.suspend();
					} catch (Exception e) {
						// ignore
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
				closeConnection();
				startReconnectThread();
			}
		}

		@Override
		public synchronized void run() {
			try {
				logger.info("Trying to reconnect " + _jmsConnectionData);
				createConnection();
				for (Entry<JMSConsumer, Boolean> entry : _jmsConsumers.entrySet()) {
					JMSConsumer jmsConsumer = entry.getKey();
					try {
						jmsConsumer.resume();
						// restore last state
						jmsConsumer.enable(entry.getValue());
					} catch (Exception e) {
						logger.error("Failed to resume " + jmsConsumer.getKey(), e);
					}
				}
				_connection.setExceptionListener(this);
				logger.info("Reconnected " + _jmsConnectionData);
				_future.cancel(false);
				_future = null;
			} catch (Exception e) {
				if (_connection != null) {
					closeConnection();
				}
				logger.error("Reconnect failed for " + _jmsConnectionData, e);
			}
		}

		public boolean isConnected() {
			return _connection != null;
		}
	}

}
