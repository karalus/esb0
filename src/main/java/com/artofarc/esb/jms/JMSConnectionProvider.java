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

import java.util.ArrayList;
import java.util.HashMap;
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

public class JMSConnectionProvider {

   protected final static Logger logger = Logger.getLogger("ESB");
   
	private final HashMap<String, JMSConnectionGuard> _pool = new HashMap<>();

	private final PoolContext _poolContext;

	public JMSConnectionProvider(PoolContext poolContext) {
		_poolContext = poolContext;
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

	public synchronized void close() {
		for (JMSConnectionGuard connectionGuard : _pool.values()) {
			try {
				connectionGuard.getConnection().close();
			} catch (JMSException e) {
				// ignore
			}
		}
		_pool.clear();
	}

	public class JMSConnectionGuard extends TimerTask implements ExceptionListener {

		private final ArrayList<JMSConsumer> jmsConsumers = new ArrayList<>();

		private final String jndiConnectionFactory;

		private volatile Connection connection;

		private ScheduledExecutorService scheduledExecutorService;

		public JMSConnectionGuard(String jndiConnectionFactory, Connection connection) throws JMSException {
			this.jndiConnectionFactory = jndiConnectionFactory;
			setConnection(connection);
		}

		public Connection getConnection() {
			return connection;
		}

		public void setConnection(Connection connection) throws JMSException {
			this.connection = connection;
			connection.setExceptionListener(this);
		}

		public void addJMSConsumer(JMSConsumer jmsConsumer) {
			jmsConsumers.add(jmsConsumer);
		}

		@Override
		public void onException(JMSException jmsException) {
			logger.warning("Connection will be closed caused by: " + jmsException);
			for (JMSConsumer jmsConsumer : jmsConsumers) {
				try {
					jmsConsumer.close();
				} catch (Exception e) {
					// ignore
				}
			}
			try {
				connection.close();
			} catch (JMSException e) {
				// ignore
			}
			// start reconnect thread
			scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
			scheduledExecutorService.scheduleAtFixedRate(this, 10, 20, TimeUnit.SECONDS);
		}

		@Override
		public void run() {
			try {
				ConnectionFactory qcf = _poolContext.getGlobalContext().lookup(jndiConnectionFactory);
				Connection connection = qcf.createConnection();
				setConnection(connection);
				for (JMSConsumer jmsConsumer : jmsConsumers) {
					jmsConsumer.open();
				}
				scheduledExecutorService.shutdown();
				scheduledExecutorService = null;
			} catch (Exception e) {
				logger.severe("Reconnecting failed: " + e);
			}
		}

	}

}
