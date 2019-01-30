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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.ExceptionListener;
import javax.jms.JMSException;
import javax.naming.NamingException;

import com.artofarc.esb.context.PoolContext;
import com.artofarc.esb.resource.JMSSessionFactory;

public final class JMSConnectionProvider {

	protected final static Logger logger = Logger.getLogger("ESB");

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
			ConnectionFactory qcf = _poolContext.getGlobalContext().lookup(jndiConnectionFactory);
			connectionGuard = new JMSConnectionGuard(jndiConnectionFactory, qcf.createConnection());
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
		private volatile Connection _connection;
		private ScheduledExecutorService _scheduledExecutorService;

		private JMSConnectionGuard(String jndiConnectionFactory, Connection connection) throws JMSException {
			_jndiConnectionFactory = jndiConnectionFactory;
			setConnection(connection);
		}

		public Connection getConnection() {
			if (_connection == null) {
				throw new IllegalStateException(_jndiConnectionFactory + " is currently invalid");
			}
			return _connection;
		}

		public void setConnection(Connection connection) throws JMSException {
			_connection = connection;
			connection.setExceptionListener(this);
		}

		public synchronized void addJMSConsumer(JMSConsumer jmsConsumer) {
			_jmsConsumers.put(jmsConsumer, null);
		}

		@Override
		public synchronized void onException(JMSException jmsException) {
			logger.warning(_jndiConnectionFactory + ": Connection will be closed caused by: " + jmsException);
			// For Oracle AQ the connection must be closed first
			try {
				logger.info("Closing Connection");
				_connection.close();
			} catch (JMSException e) {
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
					logger.info("Closing JMSConsumer");
					jmsConsumer.close();
				} catch (Exception e) {
					// ignore
				}
			}
			// start reconnect thread
			logger.info(_jndiConnectionFactory + ": start reconnect thread");
			_scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
			_scheduledExecutorService.scheduleAtFixedRate(this, 10, 20, TimeUnit.SECONDS);
		}

		@Override
		public synchronized void run() {
			try {
				logger.info("Trying to reconnect " + _jndiConnectionFactory);
				ConnectionFactory qcf = _poolContext.getGlobalContext().lookup(_jndiConnectionFactory);
				setConnection(qcf.createConnection());
				for (Entry<JMSConsumer, Boolean> entry : _jmsConsumers.entrySet()) {
					JMSConsumer jmsConsumer = entry.getKey();
					jmsConsumer.open();
					// restore last state
					jmsConsumer.enable(entry.getValue());
				}
				logger.info("Reconnected  " + _jndiConnectionFactory);
				_scheduledExecutorService.shutdown();
				_scheduledExecutorService = null;
			} catch (Exception e) {
				logger.severe(_jndiConnectionFactory + " reconnect failed: " + e);
			}
		}

	}

}
