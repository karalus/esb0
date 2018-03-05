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

import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.logging.Level;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.header.Header;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.resource.KafkaConsumerFactory;

public class KafkaConsumeAction extends TerminalAction {

	private final Properties _properties;
	private final List<String> _topics;
	private final long _timeout;
	private final String _workerPool;

	private Action _spawn;

	public KafkaConsumeAction(Properties properties, List<String> topics, long timeout, String workerPool) {
		_properties = properties;
		_topics = topics;
		_timeout = timeout;
		_workerPool = workerPool;
	}

	public void setSpawn(Action spawn) {
		_spawn = spawn;
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		KafkaConsumerFactory kafkaConsumerFactory = context.getResourceFactory(KafkaConsumerFactory.class);
		Consumer<?, ?> consumer = kafkaConsumerFactory.getResource(_properties, _topics);
		ConsumerRecords<?, ?> consumerRecords = consumer.poll(_timeout);

		HashMap<Future<Void>, ConsumerRecord<?, ?>> futures = new HashMap<>();
		for (ConsumerRecord<?, ?> record : consumerRecords) {
			final ESBMessage m = new ESBMessage(BodyType.STRING, record.value());
			for (Header header : record.headers()) {
				m.getHeaders().put(header.key(), header.value());
			}
			logger.fine("Consumer Record:(partition=" + record.partition() + ", offset=" + record.offset() + ")");
			futures.put(SpawnAction.submit(context, m, _workerPool, _spawn), record);
		}
		for (Future<Void> future : futures.keySet()) {
			try {
				SpawnAction.join(context, message, future);
			} catch (Exception e) {
				ConsumerRecord<?, ?> record = futures.get(future);
				logger.log(Level.SEVERE, "Exception processing record in partion " + record.partition() + " with offset " + record.offset(), e);
			}
		}
		consumer.commitSync();
	}

}
