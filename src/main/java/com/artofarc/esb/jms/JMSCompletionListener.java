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

import javax.jms.CompletionListener;
import javax.jms.Message;

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
					esbMessage.putVariable(ESBConstants.JMSMessageID, message.getJMSMessageID());
					action.process(context, esbMessage);
				} catch (Exception e) {
					logger.error("Exception while completing JMS send", e);
				} finally {
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
				asyncProcessingPool.restoreContext(JMSCompletionListener.this, context, esbMessage);
				try {
					Action.processException(context, esbMessage);
				} catch (Exception e) {
					logger.error("Exception while completing JMS send", e);
				} finally {
					_workerPool.releaseContext(context);
				}
			}
		});
	}

}
