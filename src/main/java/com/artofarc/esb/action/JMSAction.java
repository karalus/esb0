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
import javax.jms.Message;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.naming.NamingException;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.GlobalContext;
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
		_jndiConnectionFactory = jndiConnectionFactory;
		_isBytesMessage = isBytesMessage;
		_priority = priority;
		_timeToLive = timeToLive;
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage interaction, boolean nextActionIsPipelineStop) throws Exception {
		super.execute(context, execContext, interaction, nextActionIsPipelineStop);
		JMSSessionFactory jmsSessionFactory = context.getResourceFactory(JMSSessionFactory.class);
		Session session = jmsSessionFactory.getResource(_jndiConnectionFactory, false);
		if (_destination == null) {
			_destination = _queueName != null ? session.createQueue(_queueName) : session.createTopic(_topicName);
		}
		Message message;
		if (_isBytesMessage) {
			BytesMessage bytesMessage = session.createBytesMessage();
			bytesMessage.writeBytes(interaction.getBodyAsByteArray(context));
			message = bytesMessage;
		} else {
			message = session.createTextMessage(interaction.getBodyAsString(context));
		}
		for (Entry<String, Object> entry : interaction.getHeaders().entrySet()) {
			message.setObjectProperty(entry.getKey(), entry.getValue());
		}
		interaction.getHeaders().clear();
		interaction.reset(BodyType.INVALID, null);
		context.getTimeGauge().startTimeMeasurement();
		MessageProducer producer = session.createProducer(_destination);
		try {
			producer.send(message, Message.DEFAULT_DELIVERY_MODE, _priority, _timeToLive);
			interaction.getHeaders().put("JMSMessageID", message.getJMSMessageID());
		} finally {
			producer.close();
			context.getTimeGauge().stopTimeMeasurement("JMS send", false);
		}
	}

}
