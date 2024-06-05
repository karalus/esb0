/*
 * Copyright 2023 Andre Karalus
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
package com.artofarc.esb.jms;

import jakarta.jms.CompletionListener;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageProducer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.artofarc.esb.action.Action;
import com.artofarc.esb.context.AsyncProcessingPool;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.WorkerPool;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;

public final class JMSCompletionListener implements CompletionListener {

	private final static Logger logger = LoggerFactory.getLogger(JMSCompletionListener.class);

	private final WorkerPool _workerPool;

	public JMSCompletionListener(WorkerPool workerPool) {
		_workerPool = workerPool;
	}

	public void send(MessageProducer producer, Message message, int deliveryMode, int priority, long timeToLive) throws JMSException {
		producer.send(message, deliveryMode, priority, timeToLive, this);
	}

	@Override
	public void onCompletion(Message message) {
		_workerPool.getExecutorService().execute(new Runnable() {
		
			@Override
			public void run() {
				ESBMessage esbMessage = new ESBMessage(BodyType.INVALID, null);
				AsyncProcessingPool asyncProcessingPool = _workerPool.getAsyncProcessingPool();
				Context context = _workerPool.getContext();
				Action action = asyncProcessingPool.restoreContext(JMSCompletionListener.this, context, esbMessage);
				try {
					if (action != null) {
						esbMessage.putVariable(ESBConstants.JMSMessageID, message.getJMSMessageID());
						action.process(context, esbMessage);
					} else {
						logger.error("No AsyncContext found for JMSCompletionListener " + JMSCompletionListener.this);
					}
				} catch (Exception e) {
					logger.error("Exception while completing JMS send", e);
				} finally {
					context.getExecutionStack().clear();
					context.getStackErrorHandler().clear();
					context.getStackPos().clear();
					_workerPool.releaseContext(context);
				}
			}
		});
	}

	@Override
	public void onException(Message message, Exception exception) {
		_workerPool.getExecutorService().execute(new Runnable() {
		
			@Override
			public void run() {
				ESBMessage esbMessage = new ESBMessage(BodyType.EXCEPTION, exception);
				AsyncProcessingPool asyncProcessingPool = _workerPool.getAsyncProcessingPool();
				Context context = _workerPool.getContext();
				Action action = asyncProcessingPool.restoreContext(JMSCompletionListener.this, context, esbMessage);
				try {
					if (action != null) {
						if (!context.getStackPos().isEmpty()) {
							context.unwindStack();
						}
						Action.processException(context, esbMessage);
					} else {
						logger.error("No AsyncContext found for JMSCompletionListener " + JMSCompletionListener.this);
					}
				} catch (Exception e) {
					logger.error("Exception while completing JMS send", e);
				} finally {
					context.getExecutionStack().clear();
					context.getStackErrorHandler().clear();
					context.getStackPos().clear();
					_workerPool.releaseContext(context);
				}
			}
		});
	}

}
