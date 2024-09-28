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
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;

import com.artofarc.esb.artifact.Location;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.Evaluator;
import com.artofarc.util.StringBuilderWriter;
import com.artofarc.util.TimeGauge;

public abstract class Action extends Evaluator<ExecutionException> implements Cloneable {

	protected final static Logger logger = LoggerFactory.getLogger(Action.class);
	private final static Logger loggerTimeGauge = LoggerFactory.getLogger(Action.class.getName() + "TimeGauge");
	private final static long timeGaugeThreshold = Long.parseLong(System.getProperty("esb0.timeGauge.threshold", "250"));

	protected Action _nextAction;
	protected Action _errorHandler;
	private Location _location;

	// For JUnit
	public final Action setNextAction(Action nextAction) {
		if (nextAction.getLocation() == null) {
			StackTraceElement stackTraceElement = Thread.currentThread().getStackTrace()[2];
			nextAction.setLocation(new Location(stackTraceElement.getClassName() + "::" + stackTraceElement.getMethodName(), stackTraceElement.getLineNumber()));
		}
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
		writer.write("Headers: {");
		for (Iterator<Map.Entry<String, Object>> iter = message.getHeaders().iterator(); iter.hasNext();) {
			Map.Entry<String, Object> entry = iter.next();
			writer.append(entry.getKey()).append('=').write(String.valueOf(entry.getValue()));
			if (iter.hasNext()) {
				writer.write(", ");
			}
		}
		writer.write('}');
		logger.info(writer.toString());
		writer.reset();
		writer.write("Variables: {");
		for (Iterator<Map.Entry<String, Object>> iter = message.getVariables().entrySet().iterator(); iter.hasNext();) {
			Map.Entry<String, Object> entry = iter.next();
			if (entry.getKey().startsWith("_")) continue;
			writer.append(entry.getKey()).write('=');
			if (entry.getValue() instanceof Node) {
				context.writeBeautified((Node) entry.getValue(), writer);
			} else {
				writer.write(String.valueOf(entry.getValue()));
			}
			if (iter.hasNext()) {
				writer.write(", ");
			}
		}
		writer.write('}');
		logger.info(writer.toString());
	}

	/**
	 * The ESB0 execution engine. TODO: Increase comprehensibility.
	 */
	public final void process(Context context, ESBMessage message) throws Exception {
		final List<Action> pipeline = new ArrayList<>();
		final List<ExecutionContext> resources = new ArrayList<>();
		final Deque<Action> stackErrorHandler = context.getStackErrorHandler();
		if (getErrorHandler() != null) {
			stackErrorHandler.push(getErrorHandler());
		}
		context.pushStackPos();
		final TimeGauge timeGauge = new TimeGauge(loggerTimeGauge, timeGaugeThreshold, false);
		timeGauge.startTimeMeasurement();
		for (Action nextAction = this; nextAction != null;) {
			Action action = nextAction;
			boolean closeSilently = false;
			try {
				for (;; action = nextAction) {
					if (action != this && action.getErrorHandler() != null) {
						stackErrorHandler.push(action.getErrorHandler());
						context.pushStackPos();
					}
					final boolean isPipeline = pipeline.size() > 0;
					final ExecutionContext execContext = action.prepare(context, message, isPipeline);
					timeGauge.stopTimeMeasurement("Prepare (isPipeline=%b): %s", true, isPipeline, action);
					pipeline.add(action);
					resources.add(execContext);
					nextAction = action.nextAction(execContext);
					if (nextAction == null) {
						nextAction = context.getExecutionStack().poll();
					}
					final boolean nextActionOffersSink;
					// the nextAction can only be added if it is not offering a sink or if the current action does not stop or can write to a sink
					if (nextAction == null || (nextActionOffersSink = nextAction.isOfferingSink(context)) && !action.isStreamingToSink()
							|| action.isPipelineStop(execContext, nextAction) && !(nextActionOffersSink && action.isStreamingToSink())) {
						break;
					}
				}
				logger.debug("Pipeline fragment: {}", pipeline);
				// pipeline fragment execution phase
				final int secondLast = pipeline.size() - 2;
				for (int i = 0; i < pipeline.size(); ++i) {
					action = pipeline.get(i);
					action.execute(context, resources.get(i), message, i == secondLast);
					timeGauge.stopTimeMeasurement("Execute: %s", true, action);
				}
			} catch (Exception e) {
				if (e instanceof ExecutionException) {
					logger.info("Flow interrupted by " + e.getMessage(), e.getCause());
				} else {
					logger.info("Exception while processing " + action, e);
				}
				logESBMessage(context, message);
				closeSilently = true;
				message.reset(BodyType.EXCEPTION, e);
				if (nextAction != null) {
					// if nextAction was polled from execution stack (e.g. HttpResponseAction), if not it will be unwinded
					context.getExecutionStack().push(nextAction);
				}
				context.unwindStack();
				processException(context, message);
				break;
			} finally {
				for (int i = pipeline.size(); i > 0;) {
					action = pipeline.get(--i);
					try {
						action.close(context, resources.get(i), message, closeSilently);
						if (action != this && action.getErrorHandler() != null) {
							context.getStackPos().pop();
							stackErrorHandler.pop();
						}
					} catch (Exception e) {
						if (!closeSilently)
							throw e;
					}
					timeGauge.stopTimeMeasurement("Close: %s", true, action);
				}
			}
			pipeline.clear();
			resources.clear();
		}
		context.getStackPos().poll();
		if (getErrorHandler() != null) {
			stackErrorHandler.poll();
		}
		timeGauge.stopTimeMeasurement("Finished process: %s", false, _location != null ? _location.getServiceArtifactURI() : getClass().getSimpleName());
	}

	public static void processException(Context context, ESBMessage message) throws Exception {
		Action nextAction = context.getStackErrorHandler().poll();
		if (nextAction != null) {
			nextAction.process(context, message);
		} else {
			nextAction = context.getExecutionStack().poll();
			if (nextAction != null) {
				nextAction.process(context, message);
				context.pushStackPos();
			} else {
				throw message.<Exception> getBody();
			}
		}
	}

	// pipelining
	protected boolean _pipelineStop, _offeringSink, _streamingToSink;

	/**
	 * @param nextAction The next Action that will be processed in the flow, may be null
	 * @return true if the {@link #execute(Context, ExecutionContext, ESBMessage, boolean) execute()} method must be called before the next {@link #Action} instance can be processed
	 */
	protected boolean isPipelineStop(ExecutionContext execContext, Action nextAction) {
		return _pipelineStop;
	}

	/**
	 * @return true if {@link #prepare(Context, ESBMessage, boolean) prepare} will turn the ESBMessage into a sink when in pipeline
	 */
	protected boolean isOfferingSink(Context context) {
		return _offeringSink;
	}

	/**
	 * @return true if the {@link #execute(Context, ExecutionContext, ESBMessage, boolean) execute()} method can stream into a sink.
	 * Allows to append an {@link #isOfferingSink(Context) sink offering} {@link #Action} instance to the pipeline.
	 */
	protected final boolean isStreamingToSink() {
		return _streamingToSink;
	}

	/**
	 * @return The next Action to be processed
	 */
	protected Action nextAction(ExecutionContext execContext) {
		return _nextAction;
	}

	/**
	 * Prepare pipeline. Header and variable will be evaluated and must be copied
	 * if needed for further processing in execute.
	 * 
	 * @param inPipeline true if this is in the middle of a pipeline
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
	protected void close(Context context, ExecutionContext execContext, ESBMessage message, boolean exception) throws Exception {
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
		Action startAction = iterator.next(), action = startAction;
		while (iterator.hasNext()) {
			action = action._nextAction = iterator.next();
		}
		return startAction;
	}

	protected final void checkAtomic(Object value, String exp) throws ExecutionException {
		if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
			throw new ExecutionException(this, "Value for " + exp + " is not an atomic type: " + value.getClass());
		}
	}

	@Override
	protected ExecutionException createException(String message) {
		return new ExecutionException(this, message);
	}

}
