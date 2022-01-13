/*
 * Copyright 2022 Andre Karalus
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
package com.artofarc.esb.jms;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.naming.NamingException;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.util.ObjectPool;

public final class JMSConnectionData {

	private static final ObjectPool<JMSConnectionData> POOL = new ObjectPool<>(new java.util.HashMap<>());

	private final String _jndiConnectionFactory, _userName, _password;
	private ConnectionFactory _connectionFactory;

	@Deprecated
	public JMSConnectionData(GlobalContext globalContext, String jndiConnectionFactory, String userName, String password) throws NamingException {
		this(jndiConnectionFactory, globalContext.bindProperties(userName), globalContext.bindProperties(password));
		_connectionFactory = globalContext.lookup(jndiConnectionFactory);
	}

	private JMSConnectionData(String jndiConnectionFactory, String userName, String password) {
		_jndiConnectionFactory = jndiConnectionFactory;
		_userName = userName;
		_password = password;
	}

	public static JMSConnectionData create(GlobalContext globalContext, String jndiConnectionFactory, String userName, String password) throws NamingException {
		synchronized (POOL) {
			JMSConnectionData instance = POOL.intern(new JMSConnectionData(jndiConnectionFactory, globalContext.bindProperties(userName), globalContext.bindProperties(password)));
			if (instance._connectionFactory == null) {
				instance._connectionFactory = globalContext.lookup(jndiConnectionFactory);
			}
			return instance;
		}
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
