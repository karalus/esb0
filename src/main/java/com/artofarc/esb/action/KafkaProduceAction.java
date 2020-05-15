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

import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.Future;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.resource.KafkaProducerFactory;

public class KafkaProduceAction extends TerminalAction {

	private final String _topic;
	private final Integer _partition;
	private final boolean _binary;
	private final Producer<?, ?> _producer;

	public KafkaProduceAction(GlobalContext globalContext, Properties properties, String topic, Integer partition, boolean binary) {
		_topic = topic;
		_partition = partition;
		_binary = binary;
		KafkaProducerFactory kafkaProducerFactory = globalContext.getResourceFactory(KafkaProducerFactory.class);
		_producer = kafkaProducerFactory.getResource(properties);
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		super.execute(context, execContext, message, nextActionIsPipelineStop);
		@SuppressWarnings("rawtypes")
		ProducerRecord record = new ProducerRecord<>(_topic, _partition, message.getVariable("record.key"), _binary ? message.getBodyAsByteArray(context) : message.getBodyAsString(context));
		for (Entry<String, Object> entry : message.getHeaders()) {
			String value = entry.getValue().toString();
			record.headers().add(entry.getKey(), value.getBytes(ESBMessage.CHARSET_DEFAULT));
		}
		context.getTimeGauge().startTimeMeasurement();
		@SuppressWarnings("unchecked")
		Future<RecordMetadata> future = _producer.send(record);
		context.getTimeGauge().stopTimeMeasurement("Kafka send", true);
		RecordMetadata metadata = future.get();
		message.getVariables().put("recordMetadata", metadata);
		context.getTimeGauge().stopTimeMeasurement("Kafka Record Metadata(topic=%s, partition=%d, offset=%d)", false, metadata.topic(), metadata.partition(), metadata.offset());
	}

}
