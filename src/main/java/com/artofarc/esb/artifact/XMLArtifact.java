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

import com.artofarc.esb.context.GlobalContext;

public class XMLArtifact extends Artifact {

	public XMLArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	@Override
	protected XMLArtifact clone(FileSystem fileSystem, Directory parent) {
		return initClone(new XMLArtifact(fileSystem, parent, getName()));
	}

	@Override
	protected void validateInternal(GlobalContext globalContext) throws Exception {
	}

	protected final ArtifactURIResolver getURIResolver() {
		return new ArtifactURIResolver() {
			@Override
			public Artifact resolveArtifact(String path) {
				Artifact artifact = getArtifact(path);
				if (artifact != null) {
					addReference(artifact);
				}
				return artifact;
			}
		};
	}

	@Override
	protected void clearContent() {
	}

}
