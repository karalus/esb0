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

import javax.jms.JMSException;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.jms.JMSConnectionData;
import com.artofarc.esb.jms.JMSConnectionProvider;
import com.artofarc.esb.jms.JMSSession;

public class JMSSessionFactory extends ResourceFactory<JMSSession, JMSConnectionData, Boolean, JMSException> {

	private final Context _context;

	public JMSSessionFactory(Context context) {
		_context = context;
	}

	@Override
	protected JMSSession createResource(JMSConnectionData jmsConnectionData, Boolean transacted) throws JMSException {
		JMSConnectionProvider jmsConnectionProvider = _context.getPoolContext().getResourceFactory(JMSConnectionProvider.class);
		return jmsConnectionProvider.createSession(jmsConnectionData, this, transacted);
	}

	@Override
	public void close() {
		JMSConnectionProvider jmsConnectionProvider = _context.getPoolContext().getResourceFactory(JMSConnectionProvider.class);
		for (JMSConnectionData jmsConnectionData : getResourceDescriptors()) {
			jmsConnectionProvider.unregisterJMSSessionFactory(jmsConnectionData, this);
		}
		super.close();
	}

}
