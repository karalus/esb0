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
package com.artofarc.esb;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.ObjectName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.artofarc.esb.action.Action;
import com.artofarc.esb.action.JDBCAction;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.message.ESBMessage;

public class ConsumerPort implements AutoCloseable, com.artofarc.esb.mbean.ConsumerPortMXBean {

	protected final static Logger logger = LoggerFactory.getLogger(ConsumerPort.class);

	private final String _uri;
	private List<Action> _serviceFlow;
	protected Action _startAction;
	private volatile boolean _enabled = true;
	protected final AtomicLong _completedTaskCount = new AtomicLong();
	private final Trend _timeConsumed = new Trend(10L);

	public ConsumerPort(String uri) {
		_uri = uri;
	}

	public final String getUri() {
		return _uri;
	}

	public final long getCompletedTaskCount() {
		return _completedTaskCount.get();
	}

	public final long getExecutionTime() {
		return _timeConsumed.getCurrent();
	}

	public String getMBeanPostfix() {
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

	public final long process(Context context, ESBMessage message) throws Exception {
		try {
			return processInternal(context, message);
		} catch (Exception e) {
			JDBCAction.closeKeptConnections(context, false);
			throw e;
		} finally {
			JDBCAction.closeKeptConnections(context, true);
		}
	}

	protected final long processInternal(Context context, ESBMessage message) throws Exception {
		context.getTimeGauge().startTimeMeasurement();
		if (_startAction.getLocation() != null) {
			message.putVariable("ServiceArtifactURI", _startAction.getLocation().getServiceArtifactURI());
		}
		try {
			_startAction.process(context, message);
		} catch (Exception e) {
			throw e;
		} finally {
			_timeConsumed.accumulateAndGet(context.getTimeGauge().stopTimeMeasurement());
			context.getTimeGauge().clear();
		}
		if (context.getExecutionStack().size() > 0) {
			logger.error("ExecutionStack not empty: " + context.getExecutionStack());
			context.getExecutionStack().clear();
			throw new IllegalStateException("ExecutionStack not empty");
		}
		if (context.getStackErrorHandler().size() > 0 || context.getStackPos().size() > 0) {
			logger.error("StackErrorHandler not empty: " + context.getStackErrorHandler());
			logger.error("StackPos not empty: " + context.getStackPos());
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
