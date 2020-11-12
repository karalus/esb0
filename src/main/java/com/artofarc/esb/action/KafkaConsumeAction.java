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
package com.artofarc.esb.action;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.header.Header;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.resource.KafkaConsumerFactory;

public class KafkaConsumeAction extends TerminalAction {

	private final Properties _properties;
	private final List<String> _topics;
	private final long _timeout;
	private final String _workerPool;
	private final Action _spawn;

	public KafkaConsumeAction(Properties properties, List<String> topics, long timeout, String workerPool, Action spawn) {
		_properties = properties;
		_topics = topics;
		_timeout = timeout;
		_workerPool = workerPool;
		_spawn = spawn;
	}

	@Override
	protected long getTimeGaugeThreshold() {
		return Long.MAX_VALUE;
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		KafkaConsumerFactory kafkaConsumerFactory = context.getResourceFactory(KafkaConsumerFactory.class);
		Consumer<?, ?> consumer = kafkaConsumerFactory.getResource(_properties, _topics);
		ConsumerRecords<?, ?> consumerRecords = consumer.poll(_timeout);

		LinkedHashMap<Future<ESBMessage>, ConsumerRecord<?, ?>> futures = new LinkedHashMap<>();
		for (ConsumerRecord<?, ?> record : consumerRecords) {
			final ESBMessage msg = new ESBMessage(null, record.value());
			msg.putVariable("record.key", record.key());
			for (Header header : record.headers()) {
				msg.putHeader(header.key(), new String(header.value(), ESBMessage.CHARSET_DEFAULT));
			}
			logger.debug("Kafka Consumer Record(topic=" + record.topic() + ", partition=" + record.partition() + ", offset=" + record.offset() + ")");
			for(;;) {
				try {
					futures.put(SpawnAction.submit(context, msg, _workerPool, _spawn, Collections.<Action> emptyList(), false, true), record);
					break;
				} catch (RejectedExecutionException e) {
					logger.warn("Could not spawn to worker pool " + _workerPool);
					Thread.sleep(100L);
				}
			}
		}
		for (Future<ESBMessage> future : futures.keySet()) {
			try {
				SpawnAction.join(context, message, future, false);
			} catch (Exception e) {
				ConsumerRecord<?, ?> record = futures.get(future);
				logger.error("Exception processing record from topic " + record.topic() + " in partition " + record.partition() + " with offset " + record.offset(), e);
			}
		}
		consumer.commitSync();
	}

}
