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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringTokenizer;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import javax.naming.NamingException;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.util.ObjectPool;

public final class JMSConnectionData {

	private static final ObjectPool<List<JMSConnectionData>> POOL = new ObjectPool<>(new java.util.HashMap<>());

	private final String _jndiConnectionFactory, _userName, _password, _clientID, _string;

	private JMSConnectionData(String jndiConnectionFactory, String userName, String password, String clientID) {
		_jndiConnectionFactory = jndiConnectionFactory;
		_userName = userName;
		_password = password;
		_clientID = clientID;
		String s = userName != null ? userName + '@' + jndiConnectionFactory : jndiConnectionFactory;
		_string = clientID == null ? s : clientID.contains(s) ? clientID : s + '#' + clientID;
	}

	public static List<JMSConnectionData> create(GlobalContext globalContext, String jndiConnectionFactories, String userName, String password) throws NamingException {
		return create(globalContext, jndiConnectionFactories, userName, password, null);
	}

	public static List<JMSConnectionData> create(GlobalContext globalContext, String jndiConnectionFactories, String userName, String password, String clientID) throws NamingException {
		List<JMSConnectionData> result = new ArrayList<>();
		StringTokenizer tokenizer = new StringTokenizer(jndiConnectionFactories, ",");
		while (tokenizer.hasMoreTokens()) {
			String jndiConnectionFactory = tokenizer.nextToken();
			JMSConnectionData jmsConnectionData = new JMSConnectionData(jndiConnectionFactory, globalContext.bindProperties(userName), globalContext.bindProperties(password), globalContext.bindProperties(clientID));
			if (result.contains(jmsConnectionData)) {
				throw new IllegalArgumentException("jndiConnectionFactories must not contain duplicates");
			}
			result.add(jmsConnectionData);
		}
		if (result.isEmpty()) {
			throw new IllegalArgumentException("jndiConnectionFactories must not be empty");
		}
		return POOL.intern(result);
	}

	@Override
	public int hashCode() {
		int result = _jndiConnectionFactory.hashCode();
		if (_userName != null) {
			result ^= _userName.hashCode();
		}
		if (_clientID != null) {
			result ^= _clientID.hashCode();
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
		if (!Objects.equals(_userName, other._userName))
			return false;
		if (!Objects.equals(_password, other._password))
			return false;
		return Objects.equals(_clientID, other._clientID);
	}

	@Override
	public String toString() {
		return _string;
	}

	Connection createConnection(GlobalContext globalContext, GlobalContext.PropertyChangeListener listener) throws JMSException {
		try {
			ConnectionFactory connectionFactory = (ConnectionFactory) globalContext.getProperty(_jndiConnectionFactory);
			Connection connection = _userName != null ? connectionFactory.createConnection(_userName, _password) : connectionFactory.createConnection();
			if (_clientID != null) {
				connection.setClientID(_clientID);
			}
			globalContext.addPropertyChangeListener(_jndiConnectionFactory, listener);
			return connection;
		} catch (NamingException e) {
			throw new JMSException(e.getClass().getName() + ": " + e.getMessage());
		}
	}

}
