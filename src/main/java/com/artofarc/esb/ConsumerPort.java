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
package com.artofarc.esb;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.ObjectName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.artofarc.esb.action.Action;
import com.artofarc.esb.action.JDBCAction;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.message.ESBMessage;

public class ConsumerPort implements AutoCloseable, com.artofarc.esb.mbean.ConsumerPortMXBean {

	protected final static Logger logger = LoggerFactory.getLogger(ConsumerPort.class);

	private final String _uri;
	private List<Action> _serviceFlow;
	private Action _startAction;
	private volatile boolean _enabled = true;
	protected final AtomicLong _completedTaskCount = new AtomicLong();

	public ConsumerPort(String uri) {
		_uri = uri;
	}

	public final String getUri() {
		return _uri;
	}

	public final long getCompletedTaskCount() {
		return _completedTaskCount.get();
	}

	public final String getMBeanPostfix() {
		return ",consumerType=" + getClass().getSimpleName() + ",uri=" + ObjectName.quote(getUri());
	}

	public boolean isEnabled() {
		return _enabled;
	}

	public final void setEnabled(boolean enabled) {
		_enabled = enabled;
	}

	public void enable(boolean enable) throws Exception {
		_enabled = enable;
	}

	public final List<Action> getServiceFlow() {
		return Action.cloneService(_serviceFlow);
	}

	public final Action setServiceFlow(List<Action> service) {
		_serviceFlow = service;
		return _startAction = Action.linkList(service);
	}

	public void init(GlobalContext globalContext) throws Exception {
	}

	public void process(Context context, ESBMessage message) throws Exception {
		processInternal(context, message);
	}

	public final long processInternal(Context context, ESBMessage message) throws Exception {
		try {
			if (_startAction.getLocation() != null) {
				message.putVariable("ServiceArtifactURI", _startAction.getLocation().getServiceArtifactURI());
			}
			_startAction.process(context, message);
		} catch (Exception e) {
			JDBCAction.closeKeptConnection(message, false);
			throw e;
		} finally {
			JDBCAction.closeKeptConnection(message, true);
			context.getTimeGauge().clear();
		}
		if (context.getExecutionStack().size() > 0) {
			context.getExecutionStack().clear();
			throw new IllegalStateException("ExecutionStack not empty");
		}
		if (context.getStackErrorHandler().size() > 0 || context.getStackPos().size() > 0) {
			context.getStackErrorHandler().clear();
			context.getStackPos().clear();
			throw new IllegalStateException("StackErrorHandler not empty");
		}
		return _completedTaskCount.incrementAndGet();
	}

	// For JUnit
	public final Action setStartAction(Action... actions) {
		return setServiceFlow(java.util.Arrays.asList(actions));
	}

	@Override
	public void close() throws Exception {
	}

}
