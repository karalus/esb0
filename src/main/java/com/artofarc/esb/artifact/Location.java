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

public final class Location {

	private final String _serviceArtifactURI;
	private final int _lineNumber;
	private final Location _parent;

	public Location(String serviceArtifactURI, int lineNumber) {
		_serviceArtifactURI = serviceArtifactURI;
		_lineNumber = lineNumber;
		_parent = null;
	}

	public Location(Location clone, Location parent) {
		_serviceArtifactURI = clone._serviceArtifactURI;
		_lineNumber = clone._lineNumber;
		_parent = parent;
	}

	public String getServiceArtifactURI() {
		return _serviceArtifactURI;
	}

	@Override
	public String toString() {
		String location = _serviceArtifactURI + '@' + _lineNumber;
		return _parent != null ? location + '|' + _parent : location;
	}

}
