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
package com.artofarc.esb.action;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.jms.*;
import javax.mail.internet.MimeMultipart;
import javax.naming.NamingException;

import com.artofarc.esb.context.*;
import static com.artofarc.esb.http.HttpConstants.*;
import com.artofarc.esb.jms.JMSCompletionListener;
import com.artofarc.esb.jms.JMSConnectionData;
import com.artofarc.esb.jms.JMSConsumer;
import com.artofarc.esb.jms.JMSSession;
import com.artofarc.esb.jms.aq.AdtHelper;
import com.artofarc.esb.message.*;
import com.artofarc.esb.resource.JMSSessionFactory;
import com.artofarc.util.ByteArrayOutputStream;
import com.artofarc.util.DataStructures;
import com.sun.xml.xsom.XSSchemaSet;

public class JMSAction extends Action {

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

	private final List<JMSConnectionData> _jmsConnectionDataList;
	private final boolean _activePassive;
	private Destination _destination;
	private final String _queueName;
	private final String _topicName;
	private final boolean _isBytesMessage;
	private final int _deliveryMode;
	private final int _priority;
	private final long _timeToLive;
	private final String _deliveryDelay, _expiryQueue;
	private final String _workerPool;
	private final boolean _receiveFromTempQueue;
	private final String _replyQueue, _receiveSelector;
	private final String _multipartSubtype, _multipart;
	private final XSSchemaSet _schemaSet;
	private final AtomicInteger _pos;

	public JMSAction(GlobalContext globalContext, List<JMSConnectionData> jmsConnectionDataList, boolean activePassive, String jndiDestination, String queueName, String topicName,
			String workerPool, boolean isBytesMessage, int deliveryMode, int priority, long timeToLive, String deliveryDelay, String expiryQueue, boolean receiveFromTempQueue,
			String replyQueue, String receiveSelector, String multipartSubtype, String multipart, XSSchemaSet schemaSet) throws NamingException {

		_pipelineStop = true;
		_offeringSink = true;
		_queueName = globalContext.bindProperties(queueName);
		_topicName = globalContext.bindProperties(topicName);
		if (jndiDestination != null) {
			_destination = globalContext.lookup(jndiDestination);
		}
		_workerPool = workerPool;
		_jmsConnectionDataList = jmsConnectionDataList;
		_activePassive = activePassive;
		_pos = jmsConnectionDataList.size() > 1 ? new AtomicInteger() : null;
		_isBytesMessage = isBytesMessage;
		_deliveryMode = deliveryMode;
		_priority = priority;
		_timeToLive = timeToLive;
		_deliveryDelay = deliveryDelay;
		_expiryQueue = expiryQueue;
		_receiveFromTempQueue = receiveFromTempQueue;
		_replyQueue = replyQueue;
		_receiveSelector = receiveSelector;
		_multipartSubtype = multipartSubtype;
		_multipart = multipart;
		_schemaSet = schemaSet;
	}

	private JMSSession getJMSSession(Context context, JMSSession oldSession) throws JMSException {
		JMSSession jmsSession = context.getResource(ESBConstants.JMSSession);
		if (jmsSession != null && _jmsConnectionDataList.contains(jmsSession.getJMSConnectionData())) {
			return jmsSession;
		}
		JMSSessionFactory jmsSessionFactory = context.getResourceFactory(JMSSessionFactory.class);
		if (_activePassive) {
			try {
				jmsSession = jmsSessionFactory.getResource(_jmsConnectionDataList.get(0), false);
				if (jmsSession != oldSession && jmsSession.isConnected()) {
					return jmsSession;
				}
			} catch (JMSException e) {
				// try passive
			}
			return jmsSessionFactory.getResource(_jmsConnectionDataList.get(1), false);
		} else if (_pos != null) {
			int currentPos = _pos.getAndUpdate(old -> (old + 1) % _jmsConnectionDataList.size());
			int pos = currentPos;
			do {
				try {
					jmsSession = jmsSessionFactory.getResource(_jmsConnectionDataList.get(pos), false);
					if (jmsSession != oldSession && jmsSession.isConnected()) {
						return jmsSession;
					}
				} catch (JMSException e) {
					// try next pos
				}
				pos = (pos + 1) % _jmsConnectionDataList.size();
			} while (pos != currentPos);
			throw new JMSException("No connected JMSSession");
		} else {
			return jmsSessionFactory.getResource(_jmsConnectionDataList.get(0), false);
		}
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		if (inPipeline) {
			if (_isBytesMessage && _multipart == null) {
				JMSSession jmsSession = getJMSSession(context, null);
				BytesMessage bytesMessage = jmsSession.getSession().createBytesMessage();
				message.reset(BodyType.OUTPUT_STREAM, new BytesMessageOutputStream(bytesMessage));
				return new ExecutionContext(bytesMessage, jmsSession);
			} else {
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				message.reset(BodyType.OUTPUT_STREAM, bos);
				return new ExecutionContext(bos);
			}
		}
		return null;
	}

	@Override
	protected final void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		context.getTimeGauge().startTimeMeasurement();
		JMSSession jmsSession = execContext != null ? execContext.getResource2() : null;
		if (jmsSession == null) {
			jmsSession = getJMSSession(context, null);
		}
		final Session session = jmsSession.getSession();
		if (_workerPool != null && session.getTransacted()) {
			throw new ExecutionException(this, "Cannot send asynchronous within a transacted session");
		}
		if (_destination == null) {
			if (_queueName != null) {
				_destination = session.createQueue(_queueName);
			} else if (_topicName != null) {
				_destination = session.createTopic(_topicName);
			}
		}
		context.getTimeGauge().stopTimeMeasurement("JMS getSession", true);
		Message jmsMessage;
		if (execContext != null && execContext.getResource() instanceof Message) {
			jmsMessage = execContext.getResource();
			jmsMessage.setStringProperty(ESBConstants.Charset, message.getSinkEncoding());
		} else {
			if (execContext != null) {
				ByteArrayOutputStream bos = execContext.getResource();
				message.reset(BodyType.INPUT_STREAM, bos.getByteArrayInputStream());
			}
			if (message.getBodyType() == BodyType.INVALID) {
				jmsMessage = session.createMessage();
			} else if (_isBytesMessage) {
				BytesMessage bytesMessage = session.createBytesMessage();
				if (MimeHelper.isMimeMultipart(_multipartSubtype, message)) {
					message.determineSinkContentType();
					MimeMultipart mmp = MimeHelper.createMimeMultipart(context, message, _multipartSubtype, _multipart, message.getBodyAsByteArray(context), false, true);
					mmp.writeTo(new BytesMessageOutputStream(bytesMessage));
					message.putHeader(HTTP_HEADER_CONTENT_TYPE, unfoldHttpHeader(mmp.getContentType()));
				} else {
					// raw or not?
					message.writeTo(new BytesMessageOutputStream(bytesMessage), context);
					message.closeBody();
					bytesMessage.setStringProperty(ESBConstants.Charset, message.getSinkEncoding());
				}
				jmsMessage = bytesMessage;
			} else if (_schemaSet != null) {
				jmsMessage = AdtHelper.createAdtMessage(context, message, _schemaSet, session);
			} else {
				jmsMessage = session.createTextMessage(message.getBodyAsString(context));
			}
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
					jmsMessage.setObjectProperty(entry.getKey().replace('-', '_'), entry.getValue());
					break;
				}
			} catch (JMSException e) {
				throw new ExecutionException(this, "Could not set JMS property " + entry.getKey(), e);
			}
		}
		context.getTimeGauge().stopTimeMeasurement("JMS createMessage", true);
		message.clearHeaders();
		message.reset(BodyType.INVALID, null);
		message.setContentType(null);
		message.setCharset(null);
		try {
			send(context, message, jmsSession, jmsMessage);
		} catch (JMSException e) {
			if (_pos != null && !session.getTransacted()) {
				// retry once
				jmsSession = getJMSSession(context, jmsSession);
				send(context, message, jmsSession, jmsMessage);
			} else {
				throw e;
			}
		}
	}

	private void send(Context context, ESBMessage message, JMSSession jmsSession, Message jmsMessage) throws Exception {
		long timeLeft = message.getTimeleft(60000L).longValue();
		long timeToLive = _timeToLive;
		Long jmsExpiration = message.getVariable(ESBConstants.JMSExpiration);
		if (jmsExpiration != null && jmsExpiration > 0) {
			timeToLive = jmsExpiration - System.currentTimeMillis();
			if (timeToLive <= 0) {
				throw new ExecutionException(this, "Incoming message has expired or system clocks differ too much");
			}
		}
		Destination destination = _destination != null ? _destination : getDestination(message, jmsSession);
		if (_receiveFromTempQueue) {
			jmsMessage.setJMSReplyTo(jmsSession.getTemporaryQueue());
			jmsSession.createProducer(destination).send(jmsMessage, _deliveryMode, _priority, timeToLive);
			context.getTimeGauge().stopTimeMeasurement("JMS send", true);
			Message replyMessage = jmsSession.getConsumerForTemporaryQueue().receive(timeLeft);
			context.getTimeGauge().stopTimeMeasurement("JMS receive", false);
			if (replyMessage == null) {
				throw new ExecutionException(this, "No reply message received within given timeout");
			}
			JMSConsumer.fillESBMessage(context, message, replyMessage);
		} else if (_replyQueue != null) {
			jmsSession.createProducer(destination).send(jmsMessage, _deliveryMode, _priority, timeToLive);
			context.getTimeGauge().stopTimeMeasurement("JMS send", true);
			message.putVariable(ESBConstants.JMSMessageID, jmsMessage.getJMSMessageID());
			final Session session = jmsSession.getSession();
			Queue replyQueue = session.createQueue(_replyQueue);
			String messageSelector = _receiveSelector != null ? (String) eval(_receiveSelector, context, message) : null;
			MessageConsumer messageConsumer = session.createConsumer(replyQueue, messageSelector);
			try {
				Message replyMessage = messageConsumer.receive(timeToLive);
				context.getTimeGauge().stopTimeMeasurement("JMS receive", false);
				if (replyMessage == null) {
					throw new ExecutionException(this, "No reply message received within given timeout");
				}
				JMSConsumer.fillESBMessage(context, message, replyMessage);
			} finally {
				messageConsumer.close();
			}
		} else {
			Destination replyTo = message.getVariable(ESBConstants.JMSReplyTo);
			if (replyTo != null) {
				MessageProducer producer = jmsSession.createProducer(null);
				producer.send(replyTo, jmsMessage, _deliveryMode, _priority, timeToLive);
				message.putVariable(ESBConstants.JMSMessageID, jmsMessage.getJMSMessageID());
			} else if (destination != null) {
				MessageProducer producer = jmsSession.createProducer(destination);
				if (_deliveryDelay != null) {
					long deliveryDelay;
					if (Character.isDigit(_deliveryDelay.charAt(0))) {
						deliveryDelay = Long.parseLong(_deliveryDelay);
					} else {
						deliveryDelay = message.<Number> getVariable(_deliveryDelay).longValue();
					}
					if (timeToLive == 0 || timeToLive > deliveryDelay) {
						jmsSession.setDeliveryDelay(producer, jmsMessage, deliveryDelay);
					} else if (_expiryQueue != null) {
						producer = jmsSession.createProducer(destination = jmsSession.createQueue(_expiryQueue));
						timeToLive = Message.DEFAULT_TIME_TO_LIVE;
					} else {
						throw new ExecutionException(this, "Message is about to expire");
					}
				}
				if (_workerPool != null) {
					WorkerPool workerPool = context.getGlobalContext().getWorkerPool(_workerPool);
					AsyncProcessingPool asyncProcessingPool = workerPool.getAsyncProcessingPool();
					if (asyncProcessingPool == null) {
						throw new ExecutionException(this, "No AsyncProcessingPool in WorkerPool " + workerPool.getName());
					}
					JMSCompletionListener completionListener = new JMSCompletionListener(workerPool);
					asyncProcessingPool.saveContext(completionListener, _nextAction, DataStructures.moveToNewList(context.getExecutionStack()),
							context.getStackErrorHandler(), context.getStackPos(), message.getVariables(), System.currentTimeMillis() + timeLeft);
					completionListener.send(producer, jmsMessage, _deliveryMode, _priority, timeToLive);
				} else {
					jmsSession.send(producer, jmsMessage, _deliveryMode, _priority, timeToLive);
					message.putVariable(ESBConstants.JMSMessageID, jmsMessage.getJMSMessageID());
				}
			} else {
				throw new ExecutionException(this, "Could not determine destination");
			}
			context.getTimeGauge().stopTimeMeasurement("JMS send", false);
		}
	}

	@Override
	protected Action nextAction(ExecutionContext execContext) {
		return _workerPool == null ? super.nextAction(execContext) : null;
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
