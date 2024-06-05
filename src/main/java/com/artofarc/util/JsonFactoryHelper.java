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
package com.artofarc.util;

import java.util.HashMap;

import jakarta.json.Json;
import jakarta.json.JsonBuilderFactory;
import jakarta.json.JsonReaderFactory;
import jakarta.json.JsonWriterFactory;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonGeneratorFactory;
import jakarta.json.stream.JsonParserFactory;

import org.eclipse.parsson.api.BufferPool;

public final class JsonFactoryHelper {

	public static final JsonReaderFactory JSON_READER_FACTORY;
	public static final JsonParserFactory JSON_PARSER_FACTORY;
	public static final JsonGeneratorFactory JSON_GENERATOR_FACTORY;
	public static final JsonBuilderFactory JSON_BUILDER_FACTORY;
	public static final JsonWriterFactory JSON_WRITER_FACTORY;

	static {
		HashMap<String, Object> config = new HashMap<>();
		if (Boolean.parseBoolean(System.getProperty("esb0.jsonGlassfishAlternateBufferPool"))) {
			config.put(BufferPool.class.getName(), new BufferPool() {

				@Override
				public char[] take() {
					return new char[IOUtils.MTU];
				}

				@Override
				public void recycle(char[] buf) {
				}
			});
		}
		JSON_READER_FACTORY = Json.createReaderFactory(config);
		JSON_PARSER_FACTORY = Json.createParserFactory(config);
		JSON_BUILDER_FACTORY = Json.createBuilderFactory(config);
		if (Boolean.parseBoolean(System.getProperty("esb0.jsonPrettyPrinting", "true"))) {
			config.put(JsonGenerator.PRETTY_PRINTING, true);
		}
		JSON_GENERATOR_FACTORY = Json.createGeneratorFactory(config);
		JSON_WRITER_FACTORY = Json.createWriterFactory(config);
	}

}
