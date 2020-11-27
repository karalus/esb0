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

import java.util.Iterator;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.ESBMessage;

public class IterateAction extends Action {

	private final String _iterExp, _varName, _iterName;
	private final boolean _remove;
	private final Action _action;

	public IterateAction(String iterExp, String iterName, boolean remove, String varName, Action action) {
		_pipelineStop = true;
		_iterExp = iterExp;
		_iterName = iterName;
		_remove = remove;
		_varName = varName;
		_action = action;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		Iterator<?> iterator = message.getVariable(_iterName);
		if (iterator == null) {
			Object iterable = bindVariable(_iterExp, context, message);
			if (!(iterable instanceof Iterable)) {
				throw new ExecutionException(this, _iterExp + " is not an Iterable, but " + iterable.getClass());
			}
			iterator = ((Iterable<?>) iterable).iterator();
			message.putVariable(_iterName, iterator);
		}
		return new ExecutionContext(iterator);
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		Iterator<?> iterator = execContext.getResource();
		if (iterator.hasNext()) {
			message.putVariable(_varName, iterator.next());
			if (_remove) {
				iterator.remove();
			}
			context.getExecutionStack().push(this);
		} else {
			message.removeVariable(_varName);
			message.removeVariable(_iterName);
		}
	}

	@Override
	protected Action nextAction(ExecutionContext execContext) {
		Iterator<?> iterator = execContext.getResource();
		return iterator.hasNext() ? _action : _nextAction;
	}

}
