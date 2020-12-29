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
package com.artofarc.esb.message;

public enum BodyType {
	INVALID(false), INPUT_STREAM(true), READER(false), STRING(false), BYTES(true), DOM(false), XQ_SEQUENCE(false), OUTPUT_STREAM(true), WRITER(false),
		SOURCE(false), RESULT(false), XQ_ITEM(false), EXCEPTION(false), JDBC_RESULT(false), JSON_VALUE(false);

	private final boolean _hasCharset;

	BodyType(boolean hasCharset) {
		_hasCharset = hasCharset;
	}

	public boolean hasCharset() {
		return _hasCharset;
	}

	static BodyType detect(Object body) {
		if (body instanceof String) {
			return STRING;
		} else if (body instanceof byte[]) {
			return BYTES;
		} else if (body instanceof org.w3c.dom.Node) {
			return DOM;
		} else if (body instanceof java.io.InputStream) {
			return INPUT_STREAM;
		} else if (body == null) {
			return INVALID;
		} else {
			throw new IllegalArgumentException("BodyType cannot be auto detected: " + body.getClass().getName());
		}
	}

}
