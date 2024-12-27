/*
 * Copyright 2024 Andre Karalus
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

import javax.json.JsonBuilderFactory;
import javax.json.JsonReaderFactory;
import javax.json.JsonWriterFactory;
import javax.json.spi.JsonProvider;
import javax.json.stream.JsonGenerator;
import javax.json.stream.JsonGeneratorFactory;
import javax.json.stream.JsonParserFactory;

import org.glassfish.json.api.BufferPool;

public final class JsonFactoryHelper {

	public static final JsonProvider JSON_PROVIDER = JsonProvider.provider();
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
		JSON_READER_FACTORY = JSON_PROVIDER.createReaderFactory(config);
		JSON_PARSER_FACTORY = JSON_PROVIDER.createParserFactory(config);
		JSON_BUILDER_FACTORY = JSON_PROVIDER.createBuilderFactory(config);
		if (Boolean.parseBoolean(System.getProperty("esb0.jsonPrettyPrinting", "true"))) {
			config.put(JsonGenerator.PRETTY_PRINTING, true);
		}
		JSON_GENERATOR_FACTORY = JSON_PROVIDER.createGeneratorFactory(config);
		JSON_WRITER_FACTORY = JSON_PROVIDER.createWriterFactory(config);
	}

}
