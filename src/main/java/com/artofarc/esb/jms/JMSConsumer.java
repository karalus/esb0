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

import javax.jms.BytesMessage;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.PoolContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.resource.JMSSessionFactory;

public class JMSConsumer extends ConsumerPort {

	private final String _jndiConnectionFactory;
	private final String _jndiDestination;
	private final String _messageSelector;
	private Destination _destination;
	private final String _queueName;
	private final String _topicName;
	private final JMSWorker[] _jmsWorker;

	public JMSConsumer(String uri, String jndiConnectionFactory, String jndiDestination, String queueName, String topicName, String messageSelector, int workerCount) throws NamingException {
		super(uri);
		_jndiConnectionFactory = jndiConnectionFactory;
		_jndiDestination = jndiDestination;
		_messageSelector = messageSelector;
		_queueName = queueName;
		_topicName = topicName;
		if (jndiDestination != null) {
			InitialContext initialContext = new InitialContext();
			try {
				_destination = (Destination) initialContext.lookup(jndiDestination);
			} finally {
				initialContext.close();
			}
		}
		_jmsWorker = new JMSWorker[workerCount];
	}

	public String getKey() {
		return _jndiConnectionFactory + '|' + _jndiDestination + '|' + _messageSelector;
	}

	public void init(PoolContext poolContext) throws Exception {
		for (int i = 0; i < _jmsWorker.length; ++i) {
			_jmsWorker[i] = new JMSWorker(new Context(poolContext));
			_jmsWorker[i].open();
			if (super.isEnabled()) {
				_jmsWorker[i].startListening();
			}
		}
		poolContext.getJMSConnectionProvider().registerJMSConsumer(_jndiConnectionFactory, this);
	}

	public void open() throws Exception {
		for (int i = 0; i < _jmsWorker.length; ++i) {
			_jmsWorker[i].open();
		}
	}

	@Override
	public boolean isEnabled() {
		return _jmsWorker[0]._messageConsumer != null;
	}

	@Override
	public void enable(boolean enable) throws JMSException {
		for (int i = 0; i < _jmsWorker.length; ++i) {
			if (enable) {
				_jmsWorker[i].startListening();
			} else {
				_jmsWorker[i].stopListening();
			}
		}
	}

	public void close() throws Exception {
		for (int i = 0; i < _jmsWorker.length; ++i) {
			_jmsWorker[i].close();
		}
	}

	public void destroy() throws Exception {
		for (int i = 0; i < _jmsWorker.length; ++i) {
			try {
				_jmsWorker[i].close();
			} catch (JMSException e) {
				// ignore
			}
			_jmsWorker[i]._context.close();
		}
	}

	class JMSWorker implements MessageListener {

		private final Context _context;

		private Session _session;

		private volatile MessageConsumer _messageConsumer;

		JMSWorker(Context context) {
			_context = context;
		}

		void open() throws Exception {
			JMSSessionFactory jmsSessionFactory = _context.getResourceFactory(JMSSessionFactory.class);
			_session = jmsSessionFactory.getResource(_jndiConnectionFactory, true).getSession();
			if (_destination == null) {
				_destination = _queueName != null ? _session.createQueue(_queueName) : _session.createTopic(_topicName);
			}
		}

		void startListening() throws JMSException {
			if (_messageConsumer == null) {
				_messageConsumer = _session.createConsumer(_destination, _messageSelector);
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
					_messageConsumer.close();
				} catch (JMSException e) {
					// ignore
				}
				_messageConsumer = null;
			}
		}

		void close() throws Exception {
			stopListening();
			JMSSessionFactory jmsSessionFactory = _context.getResourceFactory(JMSSessionFactory.class);
			jmsSessionFactory.close(_jndiConnectionFactory);
		}

		@Override
		public void onMessage(Message message) {
			final ESBMessage esbMessage;
			try {
				if (message instanceof BytesMessage) {
					BytesMessage bytesMessage = (BytesMessage) message;
					byte[] ba = new byte[(int) bytesMessage.getBodyLength()];
					bytesMessage.readBytes(ba);
					esbMessage = new ESBMessage(BodyType.BYTES, ba);
				} else if (message instanceof TextMessage) {
					TextMessage textMessage = (TextMessage) message;
					esbMessage = new ESBMessage(BodyType.STRING, textMessage.getText());
				} else {
					esbMessage = new ESBMessage(BodyType.INVALID, null);
				}
				esbMessage.getHeaders().put("JMSMessageID", message.getJMSMessageID());
				for (@SuppressWarnings("unchecked")
				Enumeration<String> propertyNames = message.getPropertyNames(); propertyNames.hasMoreElements();) {
					String propertyName = propertyNames.nextElement();
					esbMessage.getHeaders().put(propertyName, message.getObjectProperty(propertyName));
				}
			} catch (JMSException e) {
				throw new RuntimeException(e);
			}

			try {
				processInternal(_context, esbMessage);
				_session.commit();
			} catch (Exception e) {
				try {
					_session.rollback();
				} catch (JMSException e1) {
					throw new RuntimeException(e1);
				}
			}
		}

	}

}
