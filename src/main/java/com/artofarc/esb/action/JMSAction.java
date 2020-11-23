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

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

import javax.jms.*;
import javax.naming.NamingException;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.jms.JMSConnectionData;
import com.artofarc.esb.jms.JMSConsumer;
import com.artofarc.esb.jms.JMSSession;
import com.artofarc.esb.message.*;
import com.artofarc.esb.resource.JMSSessionFactory;

public class JMSAction extends TerminalAction {

	static class BytesMessageOutputStream extends OutputStream {
		final BytesMessage _bytesMessage;

		BytesMessageOutputStream(BytesMessage bytesMessage) {
			_bytesMessage = bytesMessage;
		}

		@Override
		public void write(int b) throws IOException {
			try {
				_bytesMessage.writeByte((byte) b);
			} catch (JMSException e) {
				throw new IOException(e);
			}
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			try {
				_bytesMessage.writeBytes(b, off, len);
			} catch (JMSException e) {
				throw new IOException(e);
			}
		}
	}

	private final JMSConnectionData _jmsConnectionData;
	private Destination _destination;
	private final String _queueName;
	private final String _topicName;
	private final boolean _isBytesMessage;
	private final int _deliveryMode;
	private final int _priority;
	private final Long _timeToLive;
	private final boolean _receiveFromTempQueue;

	public JMSAction(GlobalContext globalContext, JMSConnectionData jmsConnectionData, String jndiDestination, String queueName, String topicName,
			boolean isBytesMessage, int deliveryMode, int priority, long timeToLive, boolean receiveFromTempQueue) throws NamingException {
		_queueName = globalContext.bindProperties(queueName);
		_topicName = globalContext.bindProperties(topicName);
		if (jndiDestination != null) {
			_destination = globalContext.lookup(jndiDestination);
		}
		_jmsConnectionData = jmsConnectionData;
		_isBytesMessage = isBytesMessage;
		_deliveryMode = deliveryMode;
		_priority = priority;
		_timeToLive = timeToLive;
		_receiveFromTempQueue = receiveFromTempQueue;
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		super.execute(context, execContext, message, nextActionIsPipelineStop);
		JMSSessionFactory jmsSessionFactory = context.getResourceFactory(JMSSessionFactory.class);
		JMSSession jmsSession = jmsSessionFactory.getResource(_jmsConnectionData, false);
		final Session session = jmsSession.getSession();
		if (_destination == null) {
			if (_queueName != null) {
				_destination = session.createQueue(_queueName);
			} else if (_topicName != null) {
				_destination = session.createTopic(_topicName);
			}
		}
		final Destination destination = _destination != null ? _destination : getDestination(message, jmsSession);
		context.getTimeGauge().startTimeMeasurement();
		Message jmsMessage;
		if (message.getBodyType() == BodyType.INVALID) {
			jmsMessage = session.createMessage();
		} else if (_isBytesMessage) {
			BytesMessage bytesMessage = session.createBytesMessage();
			message.writeTo(new BytesMessageOutputStream(bytesMessage), context);
			message.closeBody();
			jmsMessage = bytesMessage;
			jmsMessage.setStringProperty(ESBConstants.Charset, message.getCharset().name());
		} else {
			jmsMessage = session.createTextMessage(message.getBodyAsString(context));
		}
		for (Map.Entry<String, Object> entry : message.getHeaders()) {
			try {
				switch (entry.getKey()) {
				case ESBConstants.JMSCorrelationID:
					jmsMessage.setJMSCorrelationID((String) entry.getValue());
					break;
				case ESBConstants.JMSType:
					jmsMessage.setJMSType((String) entry.getValue());
					break;
				default:
					jmsMessage.setObjectProperty(entry.getKey(), entry.getValue());
					break;
				}
			} catch (JMSException e) {
				throw new ExecutionException(this, "Could not set JMS property " + entry.getKey(), e);
			}
		}
		context.getTimeGauge().stopTimeMeasurement("JMS createMessage", true);
		message.clearHeaders();
		message.reset(BodyType.INVALID, null);
		final long timeToLive = message.getTimeleft(_timeToLive).longValue();
		if (_receiveFromTempQueue) {
			jmsMessage.setJMSReplyTo(jmsSession.getTemporaryQueue());
			jmsSession.createProducer(destination).send(jmsMessage, _deliveryMode, _priority, timeToLive);
			context.getTimeGauge().stopTimeMeasurement("JMS send", true);
			Message replyMessage = jmsSession.getConsumerForTemporaryQueue().receive(timeToLive > 0L ? timeToLive : 60000L);
			context.getTimeGauge().stopTimeMeasurement("JMS receive", false);
			if (replyMessage == null) {
				throw new ExecutionException(this, "No reply message received within given timeout");
			}
			JMSConsumer.fillESBMessage(message, replyMessage);
		} else {
			if (destination != null) {
				jmsSession.createProducer(destination).send(jmsMessage, _deliveryMode, _priority, timeToLive);
			} else {
				MessageProducer producer = session.createProducer(message.<Destination> getVariable(ESBConstants.JMSReplyTo));
				producer.send(jmsMessage, _deliveryMode, _priority, timeToLive);
				producer.close();
			}
			context.getTimeGauge().stopTimeMeasurement("JMS send", false);
			message.putVariable(ESBConstants.JMSMessageID, jmsMessage.getJMSMessageID());
		}
	}

	private static Destination getDestination(ESBMessage message, JMSSession jmsSession) throws JMSException {
		String queueName = message.getVariable(ESBConstants.QueueName);
		if (queueName != null) {
			return jmsSession.createQueue(queueName);
		}			
		String topicName = message.getVariable(ESBConstants.TopicName);
		if (topicName != null) {
			return jmsSession.createTopic(topicName);
		}
		return null;
	}

}
