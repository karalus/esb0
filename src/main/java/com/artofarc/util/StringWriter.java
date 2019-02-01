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

public final class StringWriter extends java.io.Writer {

	private final StringBuilder builder;

	public StringWriter(int initialSize) {
		builder = new StringBuilder(initialSize);
	}

	@Override
	public void write(int c) {
		builder.append((char) c);
	}

	@Override
	public void write(char cbuf[], int off, int len) {
		builder.append(cbuf, off, len);
	}

	@Override
	public void write(String str) {
		builder.append(str);
	}

	@Override
	public void write(String str, int off, int len) {
		builder.append(str.substring(off, off + len));
	}

	@Override
	public StringWriter append(CharSequence s) {
		builder.append(s);
		return this;
	}

	@Override
	public StringWriter append(CharSequence s, int start, int end) {
		builder.append(s, start, end);
		return this;
	}

	@Override
	public StringWriter append(char c) {
		builder.append(c);
		return this;
	}

	@Override
	public String toString() {
		return builder.toString();
	}

	@Override
	public void flush() {
	}

	@Override
	public void close() {
	}

	public StringReader getStringReader() {
		return new StringReader(builder);
	}

}