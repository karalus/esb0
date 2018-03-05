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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;

public abstract class Action implements Cloneable {

	protected final static Logger logger = Logger.getLogger("ESB");

	protected Action _nextAction;

	// TODO: clone!
	protected Action _errorHandler;

	public Action setNextAction(Action nextAction) {
		return _nextAction = nextAction;
	}

	public Action getErrorHandler() {
		return _errorHandler;
	}

	public void setErrorHandler(Action errorHandler) {
		_errorHandler = errorHandler;
	}

	public void process(Context context, ESBMessage message) throws Exception {
		List<Action> pipeline = new ArrayList<>();
		List<ExecutionContext> resources = new ArrayList<>();
		for (Action nextAction = this; nextAction != null;) {
			boolean isPipeline = false;
			Action action = nextAction;
			boolean closeSilently = false;
			try {
				while (action != null) {
					logger.fine("Prepare: " + action.getClass());
					ExecutionContext execContext = action.prepare(context, message, isPipeline);
					pipeline.add(action);
					resources.add(execContext);
					nextAction = action.nextAction(execContext);
					if (nextAction == null) {
						nextAction = message.getTerminal();
						message.setTerminal(null);
					}
					if (action.isPipelineStop()) {
						break;
					}
					isPipeline = true;
					action = nextAction;
				}
				// process pipeline fragment
				int secondLast = pipeline.size() - 2;
				for (int i = 0; i < pipeline.size(); ++i) {
					action = pipeline.get(i);
					ExecutionContext exContext = resources.get(i);
					logger.fine("Execute: " + action.getClass());
					action.execute(context, exContext, message, i == secondLast);
				}
			} catch (Exception e) {
				logger.log(Level.INFO, "Exception while processing " + action.getClass().getSimpleName(), e);
				closeSilently = true;
				message.reset(BodyType.EXCEPTION, e);
				nextAction = action.getErrorHandler();
				if (nextAction == null) {
					nextAction = message.getTerminal();
					message.setTerminal(null);
				}
				if (nextAction != null) {
					nextAction.process(context, message);
					break;
				} else {
					throw e;
				}
			} finally {
				for (int i = 0; i < pipeline.size(); ++i) {
					action = pipeline.get(i);
					ExecutionContext exContext = resources.get(i);
					logger.fine("Close: " + action.getClass());
					try {
						action.close(exContext);
					} catch (Exception e) {
						if (!closeSilently)
							throw e;
					}
				}
			}
			pipeline.clear();
			resources.clear();
		}
	}

	public final void execute(Context context, ESBMessage message) throws Exception {
		ExecutionContext executionContext = prepare(context, message, false);
		try {
			execute(context, executionContext, message, false);
		} finally {
			close(executionContext);
		}
	}

	// pipelining
	protected boolean _pipelineStop;

	public final boolean isPipelineStop() {
		return _pipelineStop;
	}

	protected Action nextAction(ExecutionContext execContext) {
		return _nextAction;
	}

	/**
	 * Prepare pipeline. Header and variable will be evaluated and must be copied
	 * if needed for further processing in execute.
	 * 
	 * @param inPipeline
	 *           true if this is in the middle of a pipeline
	 */
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		return null;
	}

	/**
	 * Triggers execution of pipeline. ESBMessage is in the prepared state of the
	 * last Action in pipeline. Real execution happens to occur in the last
	 * action before end of pipeline.
	 * 
	 * @param nextActionIsPipelineStop
	 *           TODO
	 */
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
	}

	/**
	 * Cleanup ExecutionContext.
	 */
	protected void close(ExecutionContext execContext) throws Exception {
	}

	@Override
	public Action clone() {
		try {
			return (Action) super.clone();
		} catch (CloneNotSupportedException e) {
			throw new RuntimeException(e);
		}
	}

	public static final List<Action> cloneService(List<Action> service) {
		List<Action> clone = new ArrayList<Action>(service.size());
		for (Action action : service) {
			clone.add(action.clone());
		}
		return clone;
	}

	public final static Action linkList(List<Action> service) {
		Action startAction;
		Iterator<Action> iterator = service.iterator();
		if (!iterator.hasNext()) {
			throw new IllegalArgumentException("List must contain at least one Action");
		}
		Action action = startAction = iterator.next();
		while (iterator.hasNext()) {
			action = action.setNextAction(iterator.next());
		}
		// if (!action.isPipelineStop()) {
		// throw new IllegalArgumentException("Last action must stop pipeline");
		// }
		return startAction;
	}

}
