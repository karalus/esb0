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
package com.artofarc.esb.util;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.JsonValue.ValueType;
import javax.mail.MessagingException;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMultipart;
import javax.xml.bind.DatatypeConverter;

import com.artofarc.esb.http.HttpConstants;
import com.artofarc.util.IOUtils;

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
				IOUtils.copy(conn.getErrorStream(), System.err);
				System.err.println();
			}
			return successful;
		} catch (IOException e) {
			return null;
		}
	}

	public Boolean deployServer(String server, InputStream inputStream) throws IOException, MessagingException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		IOUtils.copy(inputStream, bos);
		MimeMultipart mimeMultipart = createMimeMultipart(bos.toByteArray());
		System.out.println("Provisioning server: " + server);
		return deploy(new URL(server), mimeMultipart);
	}

	public List<String> listFiles(String server, String path) throws IOException {
		URL url = new URL(server + path);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("GET");
		//conn.setRequestProperty("Authorization", "Basic " + DatatypeConverter.printBase64Binary((username + ":" + password).getBytes()));
		conn.setReadTimeout(30000);
		if (conn.getResponseCode() ==  HttpURLConnection.HTTP_OK) {
			try (InputStream inputStream = conn.getInputStream()) {
				String contentType = conn.getHeaderField(HttpConstants.HTTP_HEADER_CONTENT_TYPE);
				if (contentType != null && contentType.startsWith(HttpConstants.HTTP_HEADER_CONTENT_TYPE_JSON)) {
					List<String> result = new ArrayList<>();
					JsonArray jsonArray = Json.createReader(inputStream).readArray();
					for (int i = 0; i < jsonArray.size(); ++i) {
						JsonValue jsonValue = jsonArray.get(i);
						String name;
						if (jsonValue.getValueType() == ValueType.OBJECT) {
							name = jsonValue.asJsonObject().getString("name");
						} else {
							name = ((JsonString) jsonValue).getString();
						}
						result.add(name);
					}
					return result;
				} else {
					IOUtils.copy(inputStream, System.err);
					throw new IOException("No JSON received: " + contentType);
				}
			}
		} else {
			throw new IOException("ESB0 error: " + conn.getResponseMessage());
		}
	}

	public static void main(String... args) throws Exception {
		DeployTool deployTool = new DeployTool(args[1], args[2]);
		String serverUrl = args[0] + "/admin/deploy/";
		System.out.println(deployTool.listFiles(serverUrl, "consumer"));
		Boolean status;
		try (FileInputStream inputStream = new FileInputStream(args[3])) {
			status = deployTool.deployServer(serverUrl, inputStream);
		}
		if (status == null) {
			System.err.println("Server not reachable");
			System.exit(1);
		} else if (!status) {
			System.exit(2);
		}
	}

}
