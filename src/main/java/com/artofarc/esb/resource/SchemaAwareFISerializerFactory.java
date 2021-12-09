/*
 * Copyright 2021 Andre Karalus
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
package com.artofarc.esb.resource;

import javax.xml.validation.Schema;

import com.artofarc.util.SchemaAwareFastInfosetSerializer;

public class SchemaAwareFISerializerFactory extends ResourceFactory<SchemaAwareFastInfosetSerializer, Schema, Boolean, RuntimeException> {

	private static final boolean IGNORE_WHITESPACE = Boolean.parseBoolean(System.getProperty("esb0.schemaAwareFastInfosetSerializer.ignoreWhitespace"));

	@Override
	protected SchemaAwareFastInfosetSerializer createResource(Schema schema, Boolean ignoreWhitespace) {
		return new SchemaAwareFastInfosetSerializer(schema, ignoreWhitespace != null ? ignoreWhitespace : IGNORE_WHITESPACE);
	}

}
