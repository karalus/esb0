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
package com.artofarc.esb.artifact;

import jakarta.json.stream.JsonParser;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.util.JsonFactoryHelper;

public class JSONArtifact extends Artifact {

	public JSONArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	@Override
	protected void validateInternal(GlobalContext globalContext) {
		// check if the content is valid JSON
		try (JsonParser jsonParser = JsonFactoryHelper.JSON_PARSER_FACTORY.createParser(getContentAsStream())) {
			while (jsonParser.hasNext()) {
				jsonParser.next();
			}
		}
	}

	@Override
	protected JSONArtifact clone(FileSystem fileSystem, Directory parent) {
		return initClone(new JSONArtifact(fileSystem, parent, getName()));
	}

}
