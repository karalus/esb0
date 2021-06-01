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

import java.util.Properties;

import javax.mail.Authenticator;
import javax.mail.Session;

public class MailSessionFactory extends ResourceFactory<MailSessionFactory.MailSession, Properties, Authenticator, RuntimeException> {

	public static final class MailSession implements AutoCloseable {

		private final Session _session;

		public MailSession(Session session) {
			_session = session;
		}

		public Session getSession() {
			return _session;
		}

		@Override
		public void close() {
		}

	}

	@Override
	protected MailSession createResource(Properties descriptor, Authenticator authenticator) {
		return new MailSession(Session.getInstance(descriptor, authenticator));
	}

}
