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
package com.artofarc.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;

import javax.json.JsonArrayBuilder;
import javax.json.JsonException;
import javax.json.JsonObjectBuilder;
import javax.json.JsonValue;
import javax.json.stream.JsonGenerator;

public final class JsonValueGenerator implements JsonGenerator {

	private final ArrayDeque<Builder> _stack = new ArrayDeque<>();
	private String _currentKey;
	private Builder _result;

	private Builder getBuilder() {
		Builder builder = _stack.peek();
		if (builder == null) {
			builder = _result = new ArrayBuilder() {

				@Override
				JsonValue toJsonValue() {
					return arrayBuilder.build().get(0);
				}
			};
		}
		return builder;
	}

	private Builder setKey(String key) {
		if (_currentKey == null) {
			_currentKey = key;
		} else {
			throw new JsonException("key already provided");
		}
		return _stack.peek();
	}

	private String consumeKey() {
		final String key = _currentKey;
		_currentKey = null;
		return key;
	}

	private static void checkNoKey(String key) {
		if (key != null) {
			throw new JsonException("no key expected " + key);
		}
	}

	public JsonValue getJsonValue() {
		if (_result == null) {
			throw new JsonException("not base level");
		}
		checkNoKey(_result.key);
		return _result.toJsonValue();
	}

	private abstract class Builder {
		final String key = consumeKey();

		abstract void add(JsonValue value);

		abstract void add(String value);

		abstract void add(BigDecimal value);

		abstract void add(BigInteger value);

		abstract void add(int value);

		abstract void add(long value);

		abstract void add(double value);

		abstract void add(boolean value);

		abstract void addNull();

		abstract JsonValue toJsonValue();
	}

	private class ObjectBuilder extends Builder {
		final JsonObjectBuilder objectBuilder = JsonFactoryHelper.JSON_BUILDER_FACTORY.createObjectBuilder();

		@Override
		void add(JsonValue value) {
			objectBuilder.add(consumeKey(), value);
		}

		@Override
		void add(String value) {
			objectBuilder.add(consumeKey(), value);
		}

		@Override
		void add(BigDecimal value) {
			objectBuilder.add(consumeKey(), value);
		}

		@Override
		void add(BigInteger value) {
			objectBuilder.add(consumeKey(), value);
		}

		@Override
		void add(int value) {
			objectBuilder.add(consumeKey(), value);
		}

		@Override
		void add(long value) {
			objectBuilder.add(consumeKey(), value);
		}

		@Override
		void add(double value) {
			objectBuilder.add(consumeKey(), value);
		}

		@Override
		void add(boolean value) {
			objectBuilder.add(consumeKey(), value);
		}

		@Override
		void addNull() {
			objectBuilder.addNull(consumeKey());
		}

		@Override
		JsonValue toJsonValue() {
			return objectBuilder.build();
		}
	}

	private class ArrayBuilder extends Builder {
		final JsonArrayBuilder arrayBuilder = JsonFactoryHelper.JSON_BUILDER_FACTORY.createArrayBuilder();

		@Override
		void add(JsonValue value) {
			checkNoKey(_currentKey);
			arrayBuilder.add(value);
		}

		@Override
		void add(String value) {
			checkNoKey(_currentKey);
			arrayBuilder.add(value);
		}

		@Override
		void add(BigDecimal value) {
			checkNoKey(_currentKey);
			arrayBuilder.add(value);
		}

		@Override
		void add(BigInteger value) {
			checkNoKey(_currentKey);
			arrayBuilder.add(value);
		}

		@Override
		void add(int value) {
			checkNoKey(_currentKey);
			arrayBuilder.add(value);
		}

		@Override
		void add(long value) {
			checkNoKey(_currentKey);
			arrayBuilder.add(value);
		}

		@Override
		void add(double value) {
			checkNoKey(_currentKey);
			arrayBuilder.add(value);
		}

		@Override
		void add(boolean value) {
			checkNoKey(_currentKey);
			arrayBuilder.add(value);
		}

		@Override
		void addNull() {
			checkNoKey(_currentKey);
			arrayBuilder.addNull();
		}

		@Override
		JsonValue toJsonValue() {
			return arrayBuilder.build();
		}
	}

	@Override
	public void close() {
	}

	@Override
	public void flush() {
	}

	@Override
	public JsonGenerator write(JsonValue value) {
		getBuilder().add(value);
		return this;
	}

	@Override
	public JsonGenerator write(String value) {
		getBuilder().add(value);
		return this;
	}

	@Override
	public JsonGenerator write(BigDecimal value) {
		getBuilder().add(value);
		return this;
	}

	@Override
	public JsonGenerator write(BigInteger value) {
		getBuilder().add(value);
		return this;
	}

	@Override
	public JsonGenerator write(int value) {
		getBuilder().add(value);
		return this;
	}

	@Override
	public JsonGenerator write(long value) {
		getBuilder().add(value);
		return this;
	}

	@Override
	public JsonGenerator write(double value) {
		getBuilder().add(value);
		return this;
	}

	@Override
	public JsonGenerator write(boolean value) {
		getBuilder().add(value);
		return this;
	}

	@Override
	public JsonGenerator write(String key, JsonValue value) {
		setKey(key).add(value);
		return this;
	}

	@Override
	public JsonGenerator write(String key, String value) {
		setKey(key).add(value);
		return this;
	}

	@Override
	public JsonGenerator write(String key, BigInteger value) {
		setKey(key).add(value);
		return this;
	}

	@Override
	public JsonGenerator write(String key, BigDecimal value) {
		setKey(key).add(value);
		return this;
	}

	@Override
	public JsonGenerator write(String key, int value) {
		setKey(key).add(value);
		return this;
	}

	@Override
	public JsonGenerator write(String key, long value) {
		setKey(key).add(value);
		return this;
	}

	@Override
	public JsonGenerator write(String key, double value) {
		setKey(key).add(value);
		return this;
	}

	@Override
	public JsonGenerator write(String key, boolean value) {
		setKey(key).add(value);
		return this;
	}

	@Override
	public JsonGenerator writeEnd() {
		Builder builder = _stack.poll();
		if (builder == null) {
			if (_result == null) {
				throw new JsonException("not nested");
			} else {
				throw new JsonException("below base level");
			}
		}
		if (_stack.isEmpty()) {
			_result = builder;
		} else {
			setKey(builder.key).add(builder.toJsonValue());
		}
		return this;
	}

	@Override
	public JsonGenerator writeKey(String key) {
		setKey(key);
		return this;
	}

	@Override
	public JsonGenerator writeNull() {
		getBuilder().addNull();
		return this;
	}

	@Override
	public JsonGenerator writeNull(String key) {
		setKey(key).addNull();
		return this;
	}

	@Override
	public JsonGenerator writeStartArray() {
		_stack.push(new ArrayBuilder());
		return this;
	}

	@Override
	public JsonGenerator writeStartArray(String key) {
		setKey(key);
		_stack.push(new ArrayBuilder());
		return this;
	}

	@Override
	public JsonGenerator writeStartObject() {
		_stack.push(new ObjectBuilder());
		return this;
	}

	@Override
	public JsonGenerator writeStartObject(String key) {
		setKey(key);
		_stack.push(new ObjectBuilder());
		return this;
	}

}
