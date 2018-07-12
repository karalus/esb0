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
	private final Producer<?, ?> _producer;

	public KafkaProduceAction(GlobalContext globalContext, Properties properties, String topic) throws Exception {
		_topic = topic;
		KafkaProducerFactory kafkaProducerFactory = globalContext.getResourceFactory(KafkaProducerFactory.class);
		_producer = kafkaProducerFactory.getResource(properties);
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		super.execute(context, execContext, message, nextActionIsPipelineStop);
		@SuppressWarnings("rawtypes")
		final ProducerRecord record = new ProducerRecord<>(_topic, message.getVariable("record.key"), message.getBodyAsString(context));
		context.getTimeGauge().startTimeMeasurement();
		@SuppressWarnings("unchecked")
		Future<RecordMetadata> future = _producer.send(record);
		context.getTimeGauge().stopTimeMeasurement("send", true);
		RecordMetadata metadata = future.get();
		logger.fine("meta(partition=" + metadata.partition() + ", offset=" + metadata.offset() + ")");
		context.getTimeGauge().stopTimeMeasurement("get", false);
	}

}
