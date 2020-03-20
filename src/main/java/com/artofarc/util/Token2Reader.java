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
package com.artofarc.util;

import java.io.Reader;
import java.util.Iterator;

public final class Token2Reader extends Reader {

	private final Iterator<String> _iter;

	private String _token;
	private int _pos;

	public Token2Reader(Iterator<String> iter) {
		_iter = iter;
	}

	@Override
	public int read(char[] cbuf, int off, int len) {
		if (_token == null) {
			if (_iter.hasNext()) {
				_token = _iter.next();
			} else {
				return -1;
			}
		}
		int length = Math.min(_token.length() - _pos, len);
		int srcEnd = _pos + length;
		_token.getChars(_pos, srcEnd, cbuf, off);
		if (srcEnd == _token.length()) {
			_token = null;
			_pos = 0;
		} else {
			_pos = srcEnd;
		}
		return length;
	}

	@Override
	public boolean ready() {
		return true;
	}

	@Override
	public void close() {
	}

}
