/*
 * Copyright 2023 Andre Karalus
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
package com.artofarc.esb.context;

public final class ExecutionContext {

	private final Object _resource;
	private Object _resource2, _resource3;

	public ExecutionContext(Object resource) {
		_resource = resource;
	}

	public ExecutionContext(Object resource, Object resource2) {
		_resource = resource;
		_resource2 = resource2;
	}

	public void setResource2(Object resource) {
		_resource2 = resource;
	}

	public void setResource3(Object resource) {
		_resource3 = resource;
	}

	@SuppressWarnings("unchecked")
	public <R> R getResource() {
		return (R) _resource;
	}

	@SuppressWarnings("unchecked")
	public <R> R getResource2() {
		return (R) _resource2;
	}

	@SuppressWarnings("unchecked")
	public <R> R getResource3() {
		return (R) _resource3;
	}

}
