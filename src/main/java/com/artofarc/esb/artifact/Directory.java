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

import java.util.HashMap;
import java.util.Map;

import com.artofarc.esb.context.GlobalContext;

public class Directory extends Artifact {

	private final Map<String, Artifact> _artifacts = new HashMap<>();

	public Directory(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	public final Map<String, Artifact> getArtifacts() {
		return _artifacts;
	}

	@Override
	protected void validateInternal(GlobalContext globalContext) {
	}

	@Override
	protected Directory clone(FileSystem fileSystem, Directory parent) {
		Directory clone = initClone(new Directory(fileSystem, parent, getName()));
		for (Map.Entry<String, Artifact> entry : getArtifacts().entrySet()) {
			clone.getArtifacts().put(entry.getKey(), entry.getValue().clone(fileSystem, clone));
		}
		return clone;
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		// builder.append("Directory: ").append(getURI()).append('\n');
		for (Artifact artifact : _artifacts.values()) {
			builder.append(artifact);
			if (!(artifact instanceof Directory)) {
				builder.append('\n');
			}
		}
		return builder.toString();
	}

}
