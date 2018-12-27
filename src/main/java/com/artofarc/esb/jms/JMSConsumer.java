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
import java.util.Map;

import javax.jms.BytesMessage;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.naming.NamingException;

import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.PoolContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.resource.JMSSessionFactory;

public final class JMSConsumer extends ConsumerPort implements AutoCloseable, com.artofarc.esb.mbean.JMSConsumerMXBean {

	private final String _workerPool;
	private final String _jndiConnectionFactory;
	private final String _jndiDestination;
	private final String _messageSelector;
	private Destination _destination;
	private final String _queueName;
	private final String _topicName;
	private final JMSWorker[] _jmsWorker;

	public JMSConsumer(GlobalContext globalContext, String uri, String workerPool, String jndiConnectionFactory, String jndiDestination, String queueName,
			String topicName, String messageSelector, int workerCount) throws NamingException {
		super(uri);
		_workerPool = workerPool;
		_jndiConnectionFactory = jndiConnectionFactory;
		_jndiDestination = jndiDestination;
		_messageSelector = messageSelector != null ? bindProperties(messageSelector, System.getProperties()) : null;
		_queueName = queueName;
		_topicName = topicName;
		if (jndiDestination != null) {
			_destination = globalContext.lookup(jndiDestination);
		}
		_jmsWorker = new JMSWorker[workerCount];
	}

	private static String bindProperties(String exp, Map<?, ?> props) {
      StringBuilder builder = new StringBuilder();
      for (int pos = 0;;) {
         int i = exp.indexOf("${", pos);
         if (i < 0) {
            builder.append(exp.substring(pos));
            break;
         }
         builder.append(exp.substring(pos, i));
         int j = exp.indexOf('}', i);
         if (j < 0) throw new IllegalArgumentException("Matching } is missing");
         String name = exp.substring(i + 2, j);
			Object value = props.get(name);
			if (value == null) {
				throw new NullPointerException(name + " is not set");
			}
         builder.append(value);
         pos = j + 1;
      }
      return builder.toString();
	}

	public String getKey() {
		return _jndiConnectionFactory + '|' + (_jndiDestination != null ? _jndiDestination : _queueName != null ? _queueName : _topicName) + '|' + _messageSelector;
	}
	
	public int getWorkerCount() {
		return _jmsWorker.length;
	}

	public void init(GlobalContext globalContext) throws Exception {
		PoolContext poolContext = globalContext.getWorkerPool(_workerPool).getPoolContext();
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
		for (JMSWorker jmsWorker : _jmsWorker) {
			jmsWorker.open();
		}
	}

	@Override
	public boolean isEnabled() {
		return _jmsWorker[0]._messageConsumer != null;
	}

	@Override
	public void enable(boolean enable) throws JMSException {
		for (JMSWorker jmsWorker : _jmsWorker) {
			if (enable) {
				jmsWorker.startListening();
			} else {
				jmsWorker.stopListening();
			}
		}
	}

	@Override
	public void close() throws Exception {
		for (JMSWorker jmsWorker : _jmsWorker) {
			jmsWorker.close();
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
			final ESBMessage esbMessage = new ESBMessage(BodyType.INVALID, null);
			try {
				fillESBMessage(esbMessage, message);
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

	public static void fillESBMessage(ESBMessage esbMessage, Message message) throws JMSException {
		if (message instanceof BytesMessage) {
			BytesMessage bytesMessage = (BytesMessage) message;
			byte[] ba = new byte[(int) bytesMessage.getBodyLength()];
			bytesMessage.readBytes(ba);
			esbMessage.reset(BodyType.BYTES, ba);
		} else if (message instanceof TextMessage) {
			TextMessage textMessage = (TextMessage) message;
			esbMessage.reset(BodyType.STRING, textMessage.getText());
		} else {
			esbMessage.reset(BodyType.INVALID, null);
		}
		esbMessage.putVariable(ESBConstants.JMSMessageID, message.getJMSMessageID());
		esbMessage.putVariable(ESBConstants.JMSCorrelationID, message.getJMSCorrelationID());
		esbMessage.putVariable(ESBConstants.JMSReplyTo, message.getJMSReplyTo());
		for (@SuppressWarnings("unchecked")
		Enumeration<String> propertyNames = message.getPropertyNames(); propertyNames.hasMoreElements();) {
			String propertyName = propertyNames.nextElement();
			esbMessage.getHeaders().put(propertyName, message.getObjectProperty(propertyName));
		}
	}
	
}
