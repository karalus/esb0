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
package com.artofarc.esb.jms.aq;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Struct;
import java.util.Collections;

import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.Topic;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.jdbc.JDBC2XMLMapper;
import com.artofarc.esb.jdbc.JDBCConnection;
import com.artofarc.esb.jdbc.XML2JDBCMapper;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.ReflectionUtils;
import com.sun.xml.xsom.XSSchemaSet;

public class AdtHelper implements InvocationHandler {

	private final XSSchemaSet _schemaSet;
	private final Context _context;
	private final ESBMessage _message;

	private AdtHelper(XSSchemaSet schemaSet, Context context, ESBMessage message) {
		_schemaSet = schemaSet;
		_context = context;
		_message = message;
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws SQLException {
		JDBCConnection conn = new JDBCConnection(null, (Connection) args[0], false);
		_context.putResource(ESBConstants.JDBCConnections, Collections.singletonList(conn));
		XML2JDBCMapper mapper = new XML2JDBCMapper(_schemaSet, conn);
		try {
			_message.writeToSAX(mapper, _context);
		} catch (Exception e) {
			throw ReflectionUtils.convert(e, SQLException.class);
		}
		return mapper.getObject();
	}

	public static Message createAdtMessage(Context context, ESBMessage message, XSSchemaSet schemaSet, Session session) throws Exception {
		Class<?> cls = JDBCConnection.getIfcORAData();
		Object proxyInstance = Proxy.newProxyInstance(cls.getClassLoader(), new Class[] { cls }, new AdtHelper(schemaSet, context, message.clone()));
		try {
			return ReflectionUtils.invoke(session.getClass().getMethod("createORAMessage", cls), JMSException.class, session, proxyInstance);
		} catch (NoSuchMethodException e) {
			throw new JMSException("Not an Oracle AQ session");
		}
	}

	public static void parseAdtMessage(Message adtMessage, JDBC2XMLMapper mapper, Context context, ESBMessage message) throws Exception {
		try {
			Object data = ReflectionUtils.invoke(adtMessage.getClass().getMethod("getAdtPayload"), JMSException.class, adtMessage);
			Struct struct = ReflectionUtils.invoke(data.getClass().getMethod("toDatum", Connection.class), SQLException.class, data, (Connection) null);
			//message.putVariable(com.artofarc.esb.message.ESBConstants.JMSType, struct.getSQLTypeName());
			message.materializeBodyFromSource(context, mapper.createSAXSource(context, struct));
		} catch (NoSuchMethodException e) {
			throw new JMSException("Not an Oracle AQ AdtMessage");
		}
	}

	public static MessageConsumer createAdtConsumer(Session session, Destination destination, String messageSelector, boolean noLocal) throws JMSException {
		try {
			return ReflectionUtils.invoke(session.getClass().getMethod("createConsumer", Destination.class, String.class, Object.class, String.class, Boolean.TYPE), JMSException.class,
					session, destination, messageSelector, oraDataFactory, null, noLocal);
		} catch (NoSuchMethodException e) {
			throw new JMSException("Not an Oracle AQ session");
		}
	}

	public static MessageConsumer createAdtDurableSubscriber(Session session, Topic topic, String subscriberName, String messageSelector, boolean noLocal) throws JMSException {
		try {
			return ReflectionUtils.invoke(session.getClass().getMethod("createDurableSubscriber", Topic.class, String.class, String.class, Boolean.TYPE, Object.class), JMSException.class,
					session, topic, subscriberName, messageSelector, noLocal, oraDataFactory);
		} catch (NoSuchMethodException e) {
			throw new JMSException("Not an Oracle AQ session");
		}
	}

	private final static Object oraDataFactory;

	static {
		Class<?> ifc = JDBCConnection.getIfcORADataFactory();
		oraDataFactory = ifc != null ? Proxy.newProxyInstance(ifc.getClassLoader(), new Class[] { ifc }, new ORADataFactory()) : null;
	}

	static class ORADataFactory implements InvocationHandler {

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			final Class<?> ifc = JDBCConnection.getIfcORAData();
			return Proxy.newProxyInstance(ifc.getClassLoader(), new Class[] { ifc }, new ORAData(args[0]));
		}
	}

	static class ORAData implements InvocationHandler {
		final Object _datum;

		ORAData(Object datum) {
			_datum = datum;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			return _datum;
		}
	}

}
