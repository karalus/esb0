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
import java.util.logging.Logger;

import com.artofarc.esb.action.Action;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.message.ESBMessage;

public class ConsumerPort implements com.artofarc.esb.mbean.ConsumerPortMXBean {

	protected final static Logger logger = Logger.getLogger("ESB");

	private final String _uri;

	private List<Action> internalService;
	private Action _startAction;

	private volatile boolean _enabled = true;

	public ConsumerPort(String uri) {
		_uri = uri;
	}

	public final String getUri() {
		return _uri;
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

	public final List<Action> getInternalService() {
		return Action.cloneService(internalService);
	}

	public final Action setInternalService(List<Action> service) {
		internalService = service;
		return _startAction = Action.linkList(service);
	}

	public void process(Context context, ESBMessage message) throws Exception {
		processInternal(context, message);
	}

	public final void processInternal(Context context, ESBMessage message) throws Exception {
		_startAction.process(context, message);
		if (context.getExecutionStack().size() > 0) {
			throw new IllegalStateException("ExecutionStack not empty");
		}
	}

	// For JUnit
	public final Action setStartAction(Action... actions) {
		return setInternalService(java.util.Arrays.asList(actions));
	}

}
