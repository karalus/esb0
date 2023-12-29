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
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.URLUtils;

public class BranchOnPathAction extends Action {

	private final String _basePath;
	private final boolean _genericQuery;
	private final Action _defaultAction;
	private final Map<PathTemplate, Action> _branchMap = new TreeMap<>();

	public BranchOnPathAction(String basePath, Action defaultAction, boolean genericQuery) {
		_basePath = basePath;
		_defaultAction = defaultAction;
		_genericQuery = genericQuery;
	}

	public final void addBranch(String pathTemplate, Action action) {
		if (_branchMap.put(new PathTemplate(pathTemplate), action) != null) {
			throw new IllegalArgumentException("Duplicate branch pathTemplate " + pathTemplate);
		}
	}	

	@Override
	protected boolean isPipelineStop(Action nextAction) {
		return nextAction == null || nextAction.isPipelineStop(null);
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		Action action = null;
		String appendHttpUrlPath = message.getVariable(ESBConstants.appendHttpUrlPath);
		if (appendHttpUrlPath != null && appendHttpUrlPath.startsWith(_basePath)) {
			String path = appendHttpUrlPath.substring(_basePath.length());
			action = _defaultAction;
			for (Map.Entry<PathTemplate, Action> entry : _branchMap.entrySet()) {
				final Map<String, String> match = entry.getKey().match(path);
				if (match != null) {
					action = entry.getValue();
					message.getVariables().putAll(match);
					break;
				}
			}
		}
		String queryString = message.getVariable(ESBConstants.QueryString);
		if (queryString != null) {
			URLUtils.parseURLEncodedString(queryString, message.getVariables(), _genericQuery ? ESBConstants.QueryString : null);
		}
		if (_nextAction != null) {
			context.getExecutionStack().push(_nextAction);
		}
		return new ExecutionContext(action);
	}

	@Override
	protected Action nextAction(ExecutionContext execContext) {
		return execContext.getResource();
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
						result.put(item, URLUtils.decode(path.substring(pos)));
						pos = path.length();
					} else {
						if (j < 0) {
							// path too short
							return null;
						}
						result.put(item, URLUtils.decode(path.substring(pos, j)));
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

		private String getRawTemplate() {
			StringBuilder builder = new StringBuilder();
			for (int i = 0; i < _list.size(); i += 2) {
				builder.append(_list.get(i));
			}
			return builder.toString();
		}

		@Override
		public int hashCode() {
			return _list.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == null || getClass() != obj.getClass())
				return false;
			return _list.equals(((PathTemplate) obj)._list);
		}

		@Override
		public int compareTo(PathTemplate other) {
			String rawTemplate = getRawTemplate();
			String rawTemplateOther = other.getRawTemplate();
			int lenDiff = rawTemplateOther.length() - rawTemplate.length();
			return lenDiff != 0 ? lenDiff : rawTemplate.compareTo(rawTemplateOther);
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
