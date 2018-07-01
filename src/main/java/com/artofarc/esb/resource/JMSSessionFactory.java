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
package com.artofarc.esb.resource;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Session;
import javax.naming.NamingException;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.PoolContext;
import com.artofarc.esb.jms.JMSConnectionProvider;

public class JMSSessionFactory extends ResourceFactory<Session, String, Boolean> {

	private final PoolContext _poolContext;
	
	public JMSSessionFactory(Context context) {
		_poolContext = context.getPoolContext();
	}

	@Override
	protected Session createResource(String jndiConnectionFactory, Boolean transacted) throws NamingException, JMSException {
		JMSConnectionProvider jmsConnectionProvider = _poolContext.getJMSConnectionProvider();
		Connection connection = jmsConnectionProvider.getConnection(jndiConnectionFactory);
		jmsConnectionProvider.registerJMSSessionFactory(this);
		Session session = connection.createSession(transacted, transacted ? Session.SESSION_TRANSACTED : Session.AUTO_ACKNOWLEDGE);
		if (transacted) {
			connection.start();
		}
		return session;
	}

	@Override
	protected void closeResource(Session session) throws JMSException {
		session.close();
	}

}
