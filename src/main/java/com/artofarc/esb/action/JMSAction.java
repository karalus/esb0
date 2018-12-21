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
package com.artofarc.esb.action;

import java.util.Map.Entry;

import javax.jms.BytesMessage;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.Session;
import javax.naming.NamingException;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.jms.JMSSession;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.resource.JMSSessionFactory;

public class JMSAction extends TerminalAction {

	private final String _jndiConnectionFactory;
	private Destination _destination;
	private final String _queueName;
	private final String _topicName;
	private final boolean _isBytesMessage;
	private final int _priority;
	private final long _timeToLive;

	public JMSAction(GlobalContext globalContext, String jndiConnectionFactory, String jndiDestination, String queueName, String topicName,
			boolean isBytesMessage, int priority, long timeToLive) throws NamingException {
		_queueName = queueName;
		_topicName = topicName;
		if (jndiDestination != null) {
			_destination = globalContext.lookup(jndiDestination);
		}
		globalContext.lookup(jndiConnectionFactory);
		_jndiConnectionFactory = jndiConnectionFactory;
		_isBytesMessage = isBytesMessage;
		_priority = priority;
		_timeToLive = timeToLive;
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		super.execute(context, execContext, message, nextActionIsPipelineStop);
		JMSSessionFactory jmsSessionFactory = context.getResourceFactory(JMSSessionFactory.class);
		JMSSession jmsSession = jmsSessionFactory.getResource(_jndiConnectionFactory, false);
		final Session session = jmsSession.getSession();
		if (_destination == null) {
			_destination = _queueName != null ? session.createQueue(_queueName) : session.createTopic(_topicName);
		}
		context.getTimeGauge().startTimeMeasurement();
		Message jmsMessage;
		if (_isBytesMessage) {
			BytesMessage bytesMessage = session.createBytesMessage();
			bytesMessage.writeBytes(message.getBodyAsByteArray(context));
			jmsMessage = bytesMessage;
		} else {
			jmsMessage = session.createTextMessage(message.getBodyAsString(context));
		}
		for (Entry<String, Object> entry : message.getHeaders().entrySet()) {
			try {
				jmsMessage.setObjectProperty(entry.getKey(), entry.getValue());
			} catch (JMSException e) {
				throw new ExecutionException(this, "Could not set JMS property " + entry.getKey(), e);
			}
		}
		context.getTimeGauge().stopTimeMeasurement("JMS createMessage", true);
		message.getHeaders().clear();
		message.reset(BodyType.INVALID, null);
		jmsSession.createProducer(_destination).send(jmsMessage, Message.DEFAULT_DELIVERY_MODE, _priority, _timeToLive);
		message.getHeaders().put("JMSMessageID", jmsMessage.getJMSMessageID());
		context.getTimeGauge().stopTimeMeasurement("JMS send", false);
	}

}
