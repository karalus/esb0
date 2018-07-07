package com.artofarc.esb.action;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.StringTokenizer;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.ESBVariableConstants;

public class RESTAction extends Action {

	private final ArrayList<String> _fragments;

	public RESTAction(String uriTemplate) {
		if (uriTemplate != null) {
			_fragments = new ArrayList<String>();
			for (int pos = 0;;) {
				int i = uriTemplate.indexOf('{', pos);
				if (i < 0) {
					if (pos < uriTemplate.length()) {
						_fragments.add(uriTemplate.substring(pos));
					}
					break;
				}
				_fragments.add(uriTemplate.substring(pos, i));
				int j = uriTemplate.indexOf('}', i);
				if (j < 0)
					throw new IllegalArgumentException("Matching } is missing");
				String name = uriTemplate.substring(i + 1, j);
				_fragments.add(name);
				pos = j + 1;
			}
		} else {
			_fragments = null;
		}
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		if (_fragments != null) {
			String appendHttpUrlPath = message.getVariable(ESBVariableConstants.appendHttpUrlPath);
			int pos = _fragments.get(0).length();
			for (int i = 1; i < _fragments.size();) {
				String varName = _fragments.get(i++);
				String value;
				if (i < _fragments.size()) {
					String literal = _fragments.get(i++);
					int j = appendHttpUrlPath.indexOf(literal, pos);
					if (j < 0) {
						throw new ExecutionException(this, "URL path does not match " + _fragments);
					}
					value = appendHttpUrlPath.substring(pos, j);
					pos = j + literal.length();
				} else {
					value = appendHttpUrlPath.substring(pos);
				}
				message.getVariables().put(varName, value);
			}
		}
		String queryString = message.getVariable(ESBVariableConstants.QueryString);
		if (queryString != null) {
			StringTokenizer st = new StringTokenizer(queryString, "&");
			while (st.hasMoreTokens()) {
				String pair = st.nextToken();
				final int i = pair.indexOf("=");
				String key = i > 0 ? URLDecoder.decode(pair.substring(0, i), "UTF-8") : pair;
				String value = i > 0 && pair.length() > i + 1 ? URLDecoder.decode(pair.substring(i + 1), "UTF-8") : null;
				message.getVariables().put(key, value);
			}
		}
		return null;
	}

}
