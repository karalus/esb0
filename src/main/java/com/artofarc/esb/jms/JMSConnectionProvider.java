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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.ExceptionListener;
import javax.jms.JMSException;
import javax.naming.NamingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.artofarc.esb.context.PoolContext;
import com.artofarc.esb.resource.JMSSessionFactory;
import com.artofarc.util.Closer;

public final class JMSConnectionProvider {

	protected final static Logger logger = LoggerFactory.getLogger(JMSConnectionProvider.class);
	protected final static String instanceId = System.getProperty("esb0.jms.instanceId");
	
	private final HashMap<String, JMSConnectionGuard> _pool = new HashMap<>();
	private final PoolContext _poolContext;
	private final HashSet<JMSSessionFactory> _jmsSessionFactories = new HashSet<>();

	public JMSConnectionProvider(PoolContext poolContext) {
		_poolContext = poolContext;
	}

	public Set<String> getJMSSessionFactories() {
		return _pool.keySet();
	}

	public synchronized Connection getConnection(String jndiConnectionFactory) throws NamingException, JMSException {
		JMSConnectionGuard connectionGuard = _pool.get(jndiConnectionFactory);
		if (connectionGuard == null) {
			String clientID = instanceId != null ? instanceId + _poolContext.getWorkerPool().getName() : null;
			connectionGuard = new JMSConnectionGuard(jndiConnectionFactory, clientID);
			_pool.put(jndiConnectionFactory, connectionGuard);
		}
		return connectionGuard.getConnection();
	}

	public synchronized void registerJMSConsumer(String jndiConnectionFactory, JMSConsumer jmsConsumer) {
		JMSConnectionGuard connectionGuard = _pool.get(jndiConnectionFactory);
		connectionGuard.addJMSConsumer(jmsConsumer);
	}

	public synchronized void registerJMSSessionFactory(JMSSessionFactory jmsSessionFactory) {
		_jmsSessionFactories.add(jmsSessionFactory);
	}

	public synchronized void close() {
		for (JMSConnectionGuard connectionGuard : _pool.values()) {
			try {
				connectionGuard.getConnection().close();
			} catch (Exception e) {
				// ignore
			}
		}
		_pool.clear();
	}

	private final class JMSConnectionGuard extends TimerTask implements ExceptionListener {

		private final HashMap<JMSConsumer, Boolean> _jmsConsumers = new HashMap<>();
		private final String _jndiConnectionFactory;
		private final String _clientID; 
		private volatile Connection _connection;
		private ScheduledFuture<?> _future;

		private JMSConnectionGuard(String jndiConnectionFactory, String clientID) throws NamingException, JMSException {
			_jndiConnectionFactory = jndiConnectionFactory;
			_clientID = clientID;
			_connection = createConnection();
			_connection.setExceptionListener(this);
		}

		private Connection createConnection() throws NamingException, JMSException {
			ConnectionFactory qcf = _poolContext.getGlobalContext().lookup(_jndiConnectionFactory);
			Connection connection = qcf.createConnection();
			if (_clientID != null) {
				connection.setClientID(_clientID);
			}
			try {
				connection.start();
			} catch (JMSException e) {
				connection.close();
				throw e;
			}
			return connection;
		}

		Connection getConnection() {
			if (_connection == null) {
				throw new IllegalStateException(_jndiConnectionFactory + " is currently invalid");
			}
			return _connection;
		}

		synchronized void addJMSConsumer(JMSConsumer jmsConsumer) {
			_jmsConsumers.put(jmsConsumer, null);
		}

		@Override
		public synchronized void onException(JMSException jmsException) {
			logger.warn(_jndiConnectionFactory + ": Connection will be closed caused by: " + jmsException);
			// For Oracle AQ the connection must be closed first
			try {
				logger.info("Closing Connection");
				Closer closer = new Closer(_poolContext.getWorkerPool().getExecutorService());
				// Oracle AQ sometimes waits forever in close()
				if (!closer.closeWithTimeout(Closer.createAutoCloseable(_connection), 1000L)) {
					logger.warn("Possible resource leak: Could not close connection regularly within given time");
				}
			} catch (Exception e) {
				// ignore
			}
			_connection = null;
			for (JMSSessionFactory jmsSessionFactory : _jmsSessionFactories) {
				try {
					logger.info("Closing JMSSessionFactory");
					jmsSessionFactory.close(_jndiConnectionFactory);
				} catch (Exception e) {
					// ignore
				}
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
			// start reconnect thread
			logger.info(_jndiConnectionFactory + ": start reconnect thread");
			_future = _poolContext.getWorkerPool().getScheduledExecutorService().scheduleAtFixedRate(this, 60L, 60L, TimeUnit.SECONDS);
		}

		@Override
		public synchronized void run() {
			try {
				logger.info("Trying to reconnect " + _jndiConnectionFactory);
				_connection = createConnection();
				for (Entry<JMSConsumer, Boolean> entry : _jmsConsumers.entrySet()) {
					JMSConsumer jmsConsumer = entry.getKey();
					jmsConsumer.resume();
					// restore last state
					jmsConsumer.enable(entry.getValue());
				}
				_connection.setExceptionListener(this);
				logger.info("Reconnected  " + _jndiConnectionFactory);
				_future.cancel(false);
				_future = null;
			} catch (Exception e) {
				_connection = null;
				logger.error(_jndiConnectionFactory + " reconnect failed: " + e);
			}
		}
	}

}
