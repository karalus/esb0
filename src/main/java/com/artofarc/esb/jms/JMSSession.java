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
package com.artofarc.esb.jms;

import java.util.HashMap;

import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Session;

/**
 * Cache producers because it is expensive to create them.
 */
public final class JMSSession implements AutoCloseable {

	private final Session _session;
	private final HashMap<Destination, MessageProducer> _producers = new HashMap<>();

	public JMSSession(Session session) {
		_session = session;
	}
	
	public Session getSession() {
		return _session;
	}

	public MessageProducer createProducer(Destination destination) throws JMSException {
		MessageProducer producer = _producers.get(destination);
		if (producer == null) {
			producer = _session.createProducer(destination);
			_producers.put(destination, producer);
		}
		return producer;
	}

	@Override
	public void close() throws JMSException {
		_producers.clear();
		_session.close();
	}
	
}
