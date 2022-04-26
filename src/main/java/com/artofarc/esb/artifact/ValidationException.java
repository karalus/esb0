/*
 * Copyright 2022 Andre Karalus
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
package com.artofarc.esb.artifact;

public class ValidationException extends Exception {

	private static final long serialVersionUID = 1L;

	private final Artifact _artifact;
	private final Integer _lineNumber;

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

	public ValidationException(Artifact artifact, int lineNumber, String message) {
		super(message);
		_artifact = artifact;
		_lineNumber = lineNumber;
	}

	public ValidationException(Artifact artifact, int lineNumber, Throwable cause) {
		super(cause);
		_artifact = artifact;
		_lineNumber = lineNumber;
	}

	private String getArtifactLocation() {
		String s = _artifact.getURI();
		return _lineNumber != null ? s + '@' + _lineNumber : s;
	}

	@Override
	public String toString() {
		return getCause() != null ? "Could not validate artifact " + getArtifactLocation()
				: "Could not validate artifact " + getArtifactLocation() + ": " + getMessage();
	}

	@Override
	public int hashCode() {
		return _artifact.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ValidationException other = (ValidationException) obj;
		return _artifact.equals(other._artifact);
	}

}
