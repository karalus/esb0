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
package com.artofarc.esb;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.mail.MessagingException;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMultipart;
import javax.xml.bind.DatatypeConverter;

import com.artofarc.util.StreamUtils;

public class DeployTool {

	private final String username;
	private final String password;

	public DeployTool(String username, String password) {
		this.username = username;
		this.password = password;
	}

	public static MimeMultipart createMimeMultipart(byte[] ba) throws MessagingException {
		InternetHeaders headers = new InternetHeaders();
		headers.setHeader("Content-Type", "application/octet-stream");
		headers.setHeader("Content-Disposition", "form-data; name=\"file\"");
		MimeBodyPart part = new MimeBodyPart(headers, ba);
		MimeMultipart mmp = new MimeMultipart("related");
		mmp.addBodyPart(part);
		return mmp;
	}

	private Boolean deploy(URL deploymentUrl, MimeMultipart mmp) throws MessagingException {
		try {
			HttpURLConnection conn = (HttpURLConnection) deploymentUrl.openConnection();
			conn.setRequestMethod("POST");
			conn.setDoOutput(true);
			conn.setInstanceFollowRedirects(false);
			conn.setRequestProperty("Content-Type", mmp.getContentType());
			conn.setRequestProperty("Authorization", "Basic " + DatatypeConverter.printBase64Binary((username + ":" + password).getBytes()));
			conn.setReadTimeout(60000);
			mmp.writeTo(conn.getOutputStream());
			// response
			boolean successful = conn.getResponseCode() < HttpURLConnection.HTTP_BAD_REQUEST;
			if (!successful) {
				System.err.println("HTTP response code: " + conn.getResponseCode());
				StreamUtils.copy(conn.getErrorStream(), System.err);
				System.err.println();
			}
			return successful;
		} catch (IOException e) {
			return null;
		}
	}

	public Boolean deployServer(String server, InputStream inputStream) throws IOException, MessagingException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		StreamUtils.copy(inputStream, bos);
		MimeMultipart mimeMultipart = createMimeMultipart(bos.toByteArray());
		System.out.println("Provisioning server: " + server);
		return deploy(new URL(server + "/deploy"), mimeMultipart);
	}

	public static void main(String... args) throws Exception {
		DeployTool deployTool = new DeployTool(args[1], args[2]);
		Boolean status;
		try (FileInputStream inputStream = new FileInputStream(args[3])) {
			status = deployTool.deployServer(args[0] + "/admin", inputStream);
		}
		if (status == null) {
			System.err.println("Server not reachable");
			System.exit(1);
		} else if (!status) {
			System.exit(2);
		}
	}

}