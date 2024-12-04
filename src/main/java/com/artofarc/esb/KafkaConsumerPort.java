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
package com.artofarc.esb;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.message.ESBMessage;

public final class KafkaConsumerPort extends PollingConsumerPort {

	private final Properties _config;
	private final List<String> _topics;
	private final long _pollInterval, _timeout;

	public KafkaConsumerPort(String uri, String workerPool, long pollInterval, Properties config, List<String> topics, long timeout) {
		super(uri, workerPool);
		_pollInterval = pollInterval;
		_config = config;
		_topics = topics;
		_timeout = timeout;
	}

	public Properties getConfig() {
		return _config;
	}

	public List<String> getTopics() {
		return _topics;
	}

	public String getKey() {
		return _topics + "@" + _config;
	}

	@Override
	public void run() {
		try (KafkaConsumer<?, ?> consumer = new KafkaConsumer<>(_config)) {
			consumer.subscribe(_topics);
			for (LinkedHashMap<Future<Void>, ConsumerRecord<?, ?>> futures = new LinkedHashMap<>();; futures.clear()) {
				for (final ConsumerRecord<?, ?> record : consumer.poll(_pollInterval)) {
					futures.put(submit(new Callable<Void>() {

						@Override
						public Void call() throws Exception {
							ESBMessage msg = new ESBMessage(null, record.value());
							msg.putVariable("record.key", record.key());
							msg.putVariable("record.timestamp", record.timestamp());
							msg.putVariable("record.timestampType", record.timestampType());
							for (Header header : record.headers()) {
								msg.putHeader(header.key(), new String(header.value(), ESBMessage.CHARSET_DEFAULT));
							}
							logger.debug("Kafka Consumer Record(topic=" + record.topic() + ", partition=" + record.partition() + ", offset=" + record.offset() + ")");
							Context context = _workerPool.getContext();
							try {
								process(context, msg);
							} finally {
								_workerPool.releaseContext(context);
							}
							return null;
						}
					}), record);
				}
				for (Future<Void> future : futures.keySet()) {
					try {
						future.get(_timeout, TimeUnit.MILLISECONDS);
					} catch (ExecutionException e) {
						ConsumerRecord<?, ?> record = futures.get(future);
						logger.error("Exception processing record from topic " + record.topic() + " in partition " + record.partition() + " with offset " + record.offset(), e.getCause());
					} catch (TimeoutException e) {
						ConsumerRecord<?, ?> record = futures.get(future);
						logger.warn("TimeoutException processing record from topic " + record.topic() + " in partition " + record.partition() + " with offset " + record.offset());
					}
				}
				consumer.commitSync();
			}
		} catch (InterruptedException e) {
			// cancelled
		}
	}

	@Override
	public void bind(Registry registry) {
		registry.registerMBean(this, getMBeanPostfix());
		registry.registerKafkaConsumer(this);
	}

	@Override
	public void unbind(Registry registry) {
		registry.unbindKafkaConsumer(this);
	}

}
