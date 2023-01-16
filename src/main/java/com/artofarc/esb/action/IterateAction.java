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
import java.util.Iterator;
import java.util.List;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.ESBMessage;

public class IterateAction extends Action {

	private final String _iterExp, _varName, _iterName, _collectorName, _collectVar;
	private final boolean _remove;
	private final Action _action;

	public IterateAction(String iterExp, String iterName, boolean remove, String varName, String collectorName, String collectVar, Action action) {
		_pipelineStop = true;
		_iterExp = iterExp;
		_iterName = iterName;
		_collectorName = collectorName;
		_collectVar = collectVar;
		_remove = remove;
		_varName = varName;
		_action = action;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		Iterator<?> iterator = message.getVariable(_iterName);
		if (iterator == null) {
			Object iterable = eval(_iterExp, context, message);
			if (iterable instanceof List) {
				iterator = ((List<?>) iterable).listIterator();
			} else if (iterable instanceof Iterable) {
				iterator = ((Iterable<?>) iterable).iterator();
			} else {
				throw new ExecutionException(this, _iterExp + " is not an Iterable, but " + iterable.getClass());
			}
			message.putVariable(_iterName, iterator);
			if (_collectorName != null) {
				message.putVariable(_collectorName, new ArrayList<>());
			}
		} else if (_collectorName != null) {
			List<Object> collector = message.getVariable(_collectorName);
			collector.add("body".equals(_collectVar) ? message.cloneBody(context, false) : message.getVariable(_collectVar));
		}
		return new ExecutionContext(iterator);
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) {
		Iterator<?> iterator = execContext.getResource();
		if (iterator.hasNext()) {
			if ("body".equals(_varName)) {
				message.reset(null, iterator.next());
			} else {
				message.putVariable(_varName, iterator.next());
			}
			if (_remove) {
				iterator.remove();
			}
			context.getExecutionStack().push(this);
		} else {
			message.getVariables().remove(_varName);
			message.removeVariable(_iterName);
		}
	}

	@Override
	protected Action nextAction(ExecutionContext execContext) {
		Iterator<?> iterator = execContext.getResource();
		return iterator.hasNext() ? _action : _nextAction;
	}

}
