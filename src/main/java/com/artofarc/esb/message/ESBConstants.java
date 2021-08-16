/*
 * Copyright 2021 Andre Karalus
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
package com.artofarc.esb.message;

public interface ESBConstants {

	// Derived from the Servlet concepts
	String HttpMethod = "HttpMethod";

	String ContextPath = "ContextPath";

	String PathInfo = "PathInfo";

	// Raw QueryString
	String QueryString = "QueryString";

	// Supports the construction of QueryString from other variables
	String HttpQueryParameter = "HttpQueryParameter";

	String RemoteAddr = "RemoteAddr";

	String RemoteUser = "RemoteUser";

	String ClientCertificate = "ClientCertificate";

	String HttpResponseCode = "HttpResponseCode";

	String appendHttpUrlPath = "appendHttpUrlPath";

	String AsyncContext = "_AsyncContext";

	String redirect = "redirect";

	// HTTPURLConnection
	String HttpURLConnection = "_HttpURLConnection";

	String HttpURLOutbound = "HttpURLOutbound";

	// ESB0 internal concepts
	String WorkerPool = "WorkerPool";

	String initialTimestamp = "initialTimestamp";

	String initialTimestampOrigin = "initialTimestampOrigin";

	String timeleft = "timeleft";

	String timeleftOrigin = "timeleftOrigin";

	String Charset = "Charset";

	String serializationParameters = "serializationParameters";

	// SOAP processing concepts
	String SOAP_ELEMENT_NAME = "soapElementName";

	String SOAP_OPERATION = "operation";

	String SOAP_HEADER = "header";

	// JMS concepts
	String JMSMessageID = "JMSMessageID";

	String JMSCorrelationID = "JMSCorrelationID";

	String JMSReplyTo = "JMSReplyTo";

	String JMSTimestamp = "JMSTimestamp";

	String JMSType = "JMSType";

	String JMSExpiration = "JMSExpiration";

	String JMSOrigin = "JMSOrigin";

	String QueueName = "QueueName";

	String TopicName = "TopicName";

	// File concepts
	String FileEventKind = "FileEventKind";

	String filename = "filename";

	// JDBC concepts
	String JDBCConnection = "_JDBCConnection";

}
