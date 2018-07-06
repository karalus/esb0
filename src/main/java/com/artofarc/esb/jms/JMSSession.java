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
