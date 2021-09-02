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

import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.artofarc.esb.artifact.Location;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.ReflectionUtils;
import com.artofarc.util.StringBuilderWriter;
import com.artofarc.util.TimeGauge;

public abstract class Action implements Cloneable {

	protected final static Logger logger = LoggerFactory.getLogger(Action.class);

	private final static Logger loggerTimeGauge = LoggerFactory.getLogger(Action.class.getName() + "TimeGauge");
	private final static long timeGaugeThreshold = Long.parseLong(System.getProperty("esb0.timeGauge.threshold", "250"));

	protected Action _nextAction;
	protected Action _errorHandler;
	private Location _location;

	public final Action setNextAction(Action nextAction) {
		return _nextAction = nextAction;
	}

	public final Action getErrorHandler() {
		return _errorHandler;
	}

	public final void setErrorHandler(Action errorHandler) {
		_errorHandler = errorHandler;
	}

	public final Location getLocation() {
		return _location;
	}

	public final void setLocation(Location location) {
		_location = location;
	}

	@Override
	public final String toString() {
		String s = getClass().getSimpleName();
		if (_location != null) {
			s += " in ServiceArtifact " + _location;
		}
		return s;
	}

	protected static void logESBMessage(Context context, ESBMessage message) throws Exception {
		StringBuilderWriter writer = new StringBuilderWriter();
		writer.write("Headers: ");
		ESBMessage.dumpKeyValues(context, message.getHeaders(), writer);
		logger.info(writer.toString());
		writer.reset();
		writer.write("Variables: ");
		ESBMessage.dumpKeyValues(context, message.getVariables().entrySet(), writer);
		logger.info(writer.toString());
	}

	/**
	 * The ESB0 execution engine. TODO: Increase comprehensibility.
	 */
	public final void process(Context context, ESBMessage message) throws Exception {
		List<Action> pipeline = new ArrayList<>();
		List<ExecutionContext> resources = new ArrayList<>();
		Deque<Action> stackErrorHandler = context.getStackErrorHandler();
		context.pushStackPos();
		TimeGauge timeGauge = new TimeGauge(loggerTimeGauge, timeGaugeThreshold, false);
		timeGauge.startTimeMeasurement();
		for (Action nextAction = this; nextAction != null;) {
			boolean isPipeline = false;
			Action action = nextAction;
			boolean closeSilently = false;
			try {
				while (action != null) {
					if (action.getErrorHandler() != null) {
						stackErrorHandler.push(action.getErrorHandler());
						context.pushStackPos();
					}
					ExecutionContext execContext = action.prepare(context, message, isPipeline);
					timeGauge.stopTimeMeasurement("Prepare (isPipeline=%b): %s", true, isPipeline, action);
					pipeline.add(action);
					resources.add(execContext);
					nextAction = action.nextAction(execContext);
					isPipeline |= action.isPipelineStart();
					if (!isPipeline || action.isPipelineStop()) {
						break;
					}
					action = nextAction != null ? nextAction : context.getExecutionStack().poll();
				}
				// process pipeline fragment
				int secondLast = pipeline.size() - 2;
				for (int i = 0; i < pipeline.size(); ++i) {
					action = pipeline.get(i);
					ExecutionContext exContext = resources.get(i);
					action.execute(context, exContext, message, i == secondLast);
					timeGauge.stopTimeMeasurement("Execute: " + action, true);
				}
				if (nextAction == null) {
					nextAction = context.getExecutionStack().poll();
				}
			} catch (Exception e) {
				logger.info("Exception while processing " + action, e);
				logESBMessage(context, message);
				closeSilently = true;
				message.reset(BodyType.EXCEPTION, e);
				context.unwindStack();
				nextAction = stackErrorHandler.poll();
				if (nextAction != null) {
					nextAction.process(context, message);
					nextAction = null;
				} else {
					if (getErrorHandler() != null) {
						nextAction = getErrorHandler();
					} else {
						nextAction = context.getExecutionStack().poll();
					}
					if (nextAction != null) {
						nextAction.process(context, message);
						context.pushStackPos();
						break;
					} else {
						throw e;
					}
				}
			} finally {
				for (int i = pipeline.size(); i > 0;) {
					action = pipeline.get(--i);
					ExecutionContext exContext = resources.get(i);
					try {
						action.close(exContext, message, closeSilently);
						if (action.getErrorHandler() != null) {
							context.getStackPos().pop();
							stackErrorHandler.pop();
						}
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
		context.getStackPos().poll();
		timeGauge.stopTimeMeasurement("Finished process: %s", false, _location != null ? _location.getServiceArtifactURI() : getClass().getSimpleName());
	}

	// pipelining
	protected boolean _pipelineStop, _pipelineStart = true;

	protected boolean isPipelineStop() {
		return _pipelineStop;
	}

	protected final boolean isPipelineStart() {
		return _pipelineStart;
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
	 */
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
	}

	/**
	 * Cleanup resources.
	 */
	protected void close(ExecutionContext execContext, ESBMessage message, boolean exception) throws Exception {
	}

	@Override
	public Action clone() {
		try {
			return (Action) super.clone();
		} catch (CloneNotSupportedException e) {
			throw new RuntimeException(e);
		}
	}

	public static List<Action> cloneService(List<Action> service) {
		List<Action> clone = new ArrayList<>(service.size());
		for (Action action : service) {
			clone.add(action.clone());
		}
		return clone;
	}

	public static Action linkList(List<Action> service) {
		Iterator<Action> iterator = service.iterator();
		if (!iterator.hasNext()) {
			return null;
		}
		Action startAction = iterator.next();
		Action action = startAction;
		while (iterator.hasNext()) {
			action = action.setNextAction(iterator.next());
		}
		return startAction;
	}

	public final Object bindVariable(String exp, Context context, final ESBMessage message) throws Exception {
		StringBuilder builder = new StringBuilder();
		for (int pos = 0;;) {
			int i = exp.indexOf("${", pos);
			if (i < 0) {
				if (pos == 0) return exp;
				builder.append(exp.substring(pos));
				break;
			}
			if (i > pos) builder.append(exp.substring(pos, i));
			int j = exp.indexOf('}', i);
			if (j < 0) throw new IllegalArgumentException("Matching } is missing");
			String path = exp.substring(i + 2, j);
			int k = path.indexOf('.');
			String name = k < 0 ? path : path.substring(0, k);
			Object value = "body".equals(name) ? message.getBodyAsString(context)
					: "attachments".equals(name) ? message.getAttachments() : resolve(message, name, true);
			if (value == null && (k >= 0 || name.indexOf('_') >= 0 || name.startsWith("java:"))) {
				// interpret dots as separators
				value = context.getGlobalContext().getProperty(path);
				k = -1;
			}
			boolean standalone = ++j == exp.length() && pos == 0 && i == 0;
			if (value == null && !standalone) {
				throw new ExecutionException(this, "name could not be resolved: " + name);
			}
			if (k >= 0) {
				ReflectionUtils.ParamResolver<ExecutionException> paramResolver = path.indexOf('(', k) < 0 ? null
						: new ReflectionUtils.ParamResolver<ExecutionException>() {

					@Override
					public Object resolve(String param) throws ExecutionException {
						char firstChar = param.charAt(0);
						if (firstChar == '\'') {
							return param.substring(1, param.length() - 1);
						} else if (Character.isDigit(firstChar) || firstChar == '-') {
							return Integer.valueOf(param);
						}
						return Action.this.resolve(message, param, true);
					}
				};
				value = ReflectionUtils.eval(value, path.substring(k), paramResolver);
			}
			if (standalone) {
				return value;
			}
			builder.append(value.toString());
			pos = j;
		}
		return builder.toString();
	}

	protected final <T> T resolve(ESBMessage message, String name, boolean checkAmbiguity) throws ExecutionException {
		T variable = message.getVariable(name);
		if (variable != null) {
			if (checkAmbiguity) {
				T header = message.getHeader(name);
				if (header != null && !variable.equals(header)) {
					throw new ExecutionException(this, "name could not unambiguously be resolved: " + name);
				}
			}
			return variable;
		} else {
			return message.getHeader(name);
		}
	}

	protected final void checkAtomic(Object value, String exp) throws ExecutionException {
		if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
			throw new ExecutionException(this, "Value for " + exp + " is not an atomic type: " + value.getClass());
		}
	}

}
