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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.TimeGauge;

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
		TimeGauge timeGauge = new TimeGauge(Level.INFO, 250L);
		timeGauge.startTimeMeasurement();
		for (Action nextAction = this; nextAction != null;) {
			boolean isPipeline = false;
			Action action = nextAction;
			boolean closeSilently = false;
			try {
				while (action != null) {
					ExecutionContext execContext = action.prepare(context, message, isPipeline);
					timeGauge.stopTimeMeasurement("Parent: %s, prepare (isPipeline=%b): %s", true, this, isPipeline, action);
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
					action.execute(context, exContext, message, i == secondLast);
					timeGauge.stopTimeMeasurement("Execute: " + action, true);
				}
			} catch (Exception e) {
				logger.log(Level.INFO, "Exception while processing " + action, e);
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
					try {
						action.close(exContext);
					} catch (Exception e) {
						if (!closeSilently)
							throw e;
					}
					timeGauge.stopTimeMeasurement("Close: " + action, true);
				}
			}
			pipeline.clear();
			resources.clear();
		}
		timeGauge.stopTimeMeasurement("Finished process: " + getClass(), false);
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

	protected final static String bindVariable(String exp, ESBMessage message) throws Exception {
      StringBuilder builder = new StringBuilder();
      for (int pos = 0;;) {
         int i = exp.indexOf("${", pos);
         if (i < 0) {
            builder.append(exp.substring(pos));
            break;
         }
         builder.append(exp.substring(pos, i));
         int j = exp.indexOf('}', i);
         if (j < 0) throw new IllegalArgumentException("Matching } is missing");
         String path = exp.substring(i + 2, j);
         int k = path.indexOf('.');
         String name = k < 0 ? path : path.substring(0, k);
			Object value = message.getVariable(name);
			if (value == null) {
				value = message.getHeader(name);
			}
			if (value == null) {
				throw new NullPointerException(name + " is not set");
			}
			while (k >= 0) {
				int l = path.indexOf('.', ++k);
				String fragment = l < 0 ? path.substring(k) : path.substring(k, l);
				Method method = value.getClass().getMethod(fragment);
				try {
					value = method.invoke(value);
				} catch (InvocationTargetException e) {
					throw (Exception) e.getCause();
				}
				k = l;
			}
         builder.append(value);
         pos = j + 1;
      }
      return builder.toString();
	}

}
