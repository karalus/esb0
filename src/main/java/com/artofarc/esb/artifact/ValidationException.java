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
package com.artofarc.esb.artifact;

public class ValidationException extends Exception {

	private static final long serialVersionUID = 1L;

	private final Artifact _artifact;
	private final Integer _lineNumber;

	public ValidationException(Artifact artifact, String message, Throwable cause) {
		super(message, cause);
		_artifact = artifact;
		_lineNumber = null;
	}

	public ValidationException(Artifact artifact, String message) {
		super(message);
		_artifact = artifact;
		_lineNumber = null;
	}

	public ValidationException(Artifact artifact, Throwable cause) {
		super(cause);
		_artifact = artifact;
		_lineNumber = null;
	}

	public ValidationException(Artifact artifact, Integer lineNumber, Throwable cause) {
		super(cause);
		_artifact = artifact;
		_lineNumber = lineNumber;
	}

	public final String getArtifactLocation() {
		String s = _artifact.getURI();
		return _lineNumber != null ? s + '@' + _lineNumber : s;
	}

}
