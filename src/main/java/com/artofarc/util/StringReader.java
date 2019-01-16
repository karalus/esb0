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

public final class StringReader extends java.io.Reader {

	private final StringBuilder builder;

	private int length;
	private int next;
	private int mark;

	public StringReader(StringBuilder s) {
		builder = s;
		length = s.length();
	}

	@Override
	public int read() {
		if (next >= length) return -1;
		return builder.charAt(next++);
	}

	@Override
	public int read(char cbuf[], int off, int len) {
		if (next >= length) return -1;
		int n = Math.min(length - next, len);
		builder.getChars(next, next + n, cbuf, off);
		next += n;
		return n;
	}

	@Override
	public long skip(long ns) {
		if (next >= length) return 0;
		long n = Math.min(length - next, ns);
		n = Math.max(-next, n);
		next += n;
		return n;
	}

	@Override
	public boolean ready() {
		return true;
	}

	@Override
	public boolean markSupported() {
		return true;
	}

	@Override
	public void mark(int readAheadLimit) {
		if (readAheadLimit < 0) {
			throw new IllegalArgumentException("Read-ahead limit < 0");
		}
		mark = next;
	}

	@Override
	public void reset() {
		next = mark;
	}

	@Override
	public void close() {
	}

}
