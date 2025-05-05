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
package com.artofarc.esb.resource;

import javax.jms.JMSException;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.jms.JMSConnectionData;
import com.artofarc.esb.jms.JMSConnectionProvider;
import com.artofarc.esb.jms.JMSSession;

public class JMSSessionFactory extends ResourceFactory<JMSSession, JMSConnectionData, Boolean, JMSException> {

	private final JMSConnectionProvider _jmsConnectionProvider;

	public JMSSessionFactory(Context context) {
		_jmsConnectionProvider = context.getPoolContext().getResourceFactory(JMSConnectionProvider.class);
	}

	public JMSConnectionProvider getJMSConnectionProvider() {
		return _jmsConnectionProvider;
	}

	@Override
	protected JMSSession createResource(JMSConnectionData jmsConnectionData, Boolean transacted) throws JMSException {
		return new JMSSession(this, jmsConnectionData, Boolean.TRUE == transacted);
	}

	@Override
	public void close() {
		for (JMSConnectionData jmsConnectionData : getResourceDescriptors()) {
			_jmsConnectionProvider.unregisterJMSSessionFactory(jmsConnectionData, this);
		}
		super.close();
	}

	public void commitTransactedJMSSessions(JMSSession except) throws JMSException {
		for (JMSSession jmsSession : getResources()) {
			if (jmsSession != except && jmsSession.isTransacted()) {
				jmsSession.getSession().commit();
			}
		}
	}

	public void rollbackTransactedJMSSessions(JMSSession except) {
		for (JMSSession jmsSession : getResources()) {
			if (jmsSession != except && jmsSession.isTransacted()) {
				try {
					jmsSession.getSession().rollback();
				} catch (JMSException e) {
					// ignore
				}
			}
		}
	}

}
