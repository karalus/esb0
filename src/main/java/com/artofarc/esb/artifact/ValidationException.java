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

	private final String _uri;
	private final int _lineNumber;

	ValidationException(Artifact artifact, String message) {
		this(artifact, -1, message);
	}

	ValidationException(Artifact artifact, Throwable cause) {
		this(artifact, -1, cause);
	}

	ValidationException(Artifact artifact, int lineNumber, String message) {
		super(message);
		_uri = artifact.getURI();
		_lineNumber = lineNumber;
	}

	ValidationException(Artifact artifact, int lineNumber, Throwable cause) {
		this(artifact.getURI(), lineNumber, cause);
	}

	ValidationException(String artifactUri, int lineNumber, Throwable cause) {
		super(cause);
		_uri = artifactUri;
		_lineNumber = lineNumber;
	}

	private String getArtifactLocation() {
		return _lineNumber < 0 ? _uri : _uri + '@' + _lineNumber ;
	}

	@Override
	public String toString() {
		return getCause() != null ? "Could not validate artifact " + getArtifactLocation()
				: "Could not validate artifact " + getArtifactLocation() + ": " + getMessage();
	}

	@Override
	public int hashCode() {
		return _uri.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == null || getClass() != obj.getClass())
			return false;
		return _uri.equals(((ValidationException) obj)._uri);
	}

}
