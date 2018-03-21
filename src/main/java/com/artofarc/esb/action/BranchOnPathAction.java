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
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.ESBVariableConstants;

public class BranchOnPathAction extends Action {

	private final String _basePath;
	
	private final Map<PathTemplate, Action> _branchMap = new TreeMap<>();

	private final Action _defaultAction;

	public BranchOnPathAction(String basePath, Action defaultAction) {
		_basePath = basePath;
		_defaultAction = defaultAction;
	}

	public Map<PathTemplate, Action> getBranchMap() {
		return _branchMap;
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		String pathInfo = message.getVariable(ESBVariableConstants.PathInfo);
		if (!pathInfo.startsWith(_basePath)) {
			throw new ExecutionException(this, "Base path does not match: " + pathInfo);
		}
		String path = pathInfo.substring(_basePath.length());
		Action action = _defaultAction;
		for (Entry<PathTemplate, Action> entry : _branchMap.entrySet()) {
			final Map<String, String> match = entry.getKey().match(path);
			if (match != null) {
				action = entry.getValue();
				message.getVariables().putAll(match);
				break;
			}
		}
		if (action == null) {
			action = _errorHandler;
		}
		return new ExecutionContext(action);
	}

	@Override
	protected Action nextAction(ExecutionContext execContext) {
		Action action = execContext.getResource();
		return action != null ? action : super.nextAction(execContext);
	}

	public static final class PathTemplate implements Comparable<PathTemplate> {
		
		private final ArrayList<String> _list = new ArrayList<>();
		
		public PathTemplate(String pathTemplate) {
			int pos = 0;
			boolean cBopen = false;
			for (int i = 0; i < pathTemplate.length(); ++i) {
				switch (pathTemplate.charAt(i)) {
				case '{':
					if (cBopen) throw new IllegalArgumentException("curly brace already open");
					cBopen = true;
					_list.add(pathTemplate.substring(pos, i));
					pos = i + 1;
					break;
				case '}':
					if (!cBopen) throw new IllegalArgumentException("curly brace already closed");
					cBopen = false;
					_list.add(pathTemplate.substring(pos, i));
					pos = i + 1;
					break;
				}
			}
			if (cBopen) throw new IllegalArgumentException("curly brace not closed");
			if (pos < pathTemplate.length()) {
				_list.add(pathTemplate.substring(pos));
			}
		}
		
		public Map<String, String> match(String path) {
			final Map<String, String> result = new HashMap<>();
			int pos = 0;
			for (int i = 0; i < _list.size(); ) {
				final String item = _list.get(i++);
				final boolean isTemplate = (i & 1) == 0;
				if (isTemplate) {
					final int j = path.indexOf('/', pos);
					final boolean isLast = i == _list.size();
					if (isLast) {
						if (j >= 0) {
							// path is longer
							return null;
						}
						result.put(item, path.substring(pos));
						pos = path.length();
					} else {
						if (j < 0) {
							// path too short
							return null;
						}
						result.put(item, path.substring(pos, j));
						pos = j;
					}
				} else {
					if (!path.startsWith(item, pos)) {
						return null;
					}
					pos += item.length();
				}
			}
			return pos < path.length() ? null : result;
		}
		
		private int getRawLength() {
			int l = 0;
			for (int i = 0; i < _list.size(); i += 2) {
				l += _list.get(i).length();
			}
			return l;
		}

		@Override
		public int compareTo(PathTemplate o) {
			return o.getRawLength() - getRawLength();
		}

		@Override
		public String toString() {
			StringBuilder builder = new StringBuilder();
			for (int i = 0; i < _list.size(); ) {
				final String item = _list.get(i++);
				final boolean isTemplate = (i & 1) == 0;
				if (isTemplate) {
					builder.append('{').append(item).append('}');
				} else {
					builder.append(item);
				}
			}
			return builder.toString();
		}

	}
	
}
