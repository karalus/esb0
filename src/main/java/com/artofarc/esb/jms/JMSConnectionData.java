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

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.naming.NamingException;

import com.artofarc.esb.context.GlobalContext;

public final class JMSConnectionData {

	private final String _jndiConnectionFactory, _userName, _password;
	private final ConnectionFactory _connectionFactory;

	public JMSConnectionData(GlobalContext globalContext, String jndiConnectionFactory, String userName, String password) throws NamingException {
		_connectionFactory = globalContext.lookup(_jndiConnectionFactory = jndiConnectionFactory);
		_userName = globalContext.bindProperties(userName);
		_password = globalContext.bindProperties(password);
	}

	@Override
	public int hashCode() {
		int result = _jndiConnectionFactory.hashCode();
		if (_userName != null) {
			result ^= _userName.hashCode();
		}
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!(obj instanceof JMSConnectionData))
			return false;
		JMSConnectionData other = (JMSConnectionData) obj;
		if (!_jndiConnectionFactory.equals(other._jndiConnectionFactory))
			return false;
		if (_userName == null) {
			if (other._userName != null)
				return false;
		} else if (!_userName.equals(other._userName))
			return false;
		if (_password == null) {
			return other._password == null;
		} else return _password.equals(other._password);
	}

	@Override
	public String toString() {
		return _userName != null ? _userName + '@' + _jndiConnectionFactory : _jndiConnectionFactory;
	}

	Connection createConnection() throws JMSException {
		return _userName != null ? _connectionFactory.createConnection(_userName, _password) : _connectionFactory.createConnection();
	}

}
