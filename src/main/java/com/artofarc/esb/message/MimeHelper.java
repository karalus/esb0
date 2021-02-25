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
package com.artofarc.esb.message;

import java.util.Enumeration;
import java.util.Iterator;
import java.util.Map.Entry;

import javax.mail.Header;
import javax.mail.MessagingException;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;

import com.artofarc.esb.context.Context;
import com.artofarc.util.ByteArrayOutputStream;

import static com.artofarc.esb.http.HttpConstants.*;

public final class MimeHelper {

	private static final String ROOTPART = "<rootpart@artofarc.com>";
	private static final String APPLICATION = "application/";
	private static final String TEXT = "text/";

	static MimeBodyPart createMimeBodyPart(String contentID, String contentType, byte[] content, String name) throws MessagingException {
		InternetHeaders headers = new InternetHeaders();
		headers.setHeader(HTTP_HEADER_CONTENT_TYPE, contentType);
		headers.setHeader(HTTP_HEADER_CONTENT_TRANSFER_ENCODING, "binary");
		MimeBodyPart part = new MimeBodyPart(headers, content);
		part.setContentID(contentID);
		if (name != null) {
			part.setDisposition(MimeBodyPart.ATTACHMENT + "; " + HTTP_HEADER_CONTENT_PARAMETER_NAME + '"' + name + '"');
		}
		return part;
	}

	public static String getDispositionName(MimeBodyPart bodyPart) throws MessagingException {
		return getValueFromHttpHeader(bodyPart.getHeader(HTTP_HEADER_CONTENT_DISPOSITION, null), HTTP_HEADER_CONTENT_PARAMETER_NAME);
	}

	public static MimeMultipart createMimeMultipart(Context context, ESBMessage message, String multipartContentType, ByteArrayOutputStream bos) throws Exception {
		String contentType = message.removeHeader(HTTP_HEADER_CONTENT_TYPE);
		MimeMultipart mmp = new MimeMultipart("related; " + HTTP_HEADER_CONTENT_TYPE_PARAMETER_TYPE + '"' + multipartContentType + "\"; " + HTTP_HEADER_CONTENT_TYPE_PARAMETER_START + '"' + ROOTPART
				+ "\"; " + HTTP_HEADER_CONTENT_TYPE_PARAMETER_START_INFO + '"' + contentType + '"');
		if (bos == null) {
			bos = new ByteArrayOutputStream();
			message.writeTo(bos, context);
			message.closeBody();
		}
		if (!multipartContentType.equals(contentType)) {
			contentType = multipartContentType + "; " + HTTP_HEADER_CONTENT_TYPE_PARAMETER_TYPE + '"' + contentType + '"';
		}
		MimeBodyPart part = createMimeBodyPart(ROOTPART, contentType + "; " + HTTP_HEADER_CONTENT_TYPE_PARAMETER_CHARSET + message.getSinkEncoding(), bos.toByteArray(), null);
		for (Entry<String, Object> entry : message.getHeaders()) {
			part.setHeader(entry.getKey(), entry.getValue().toString());
		}
		mmp.addBodyPart(part);
		for (Iterator<MimeBodyPart> iter = message.getAttachments().values().iterator(); iter.hasNext();) {
			MimeBodyPart bodyPart = iter.next();
			mmp.addBodyPart(bodyPart);
			iter.remove();
		}
		return mmp;
	}

	public static boolean parseMultipart(ESBMessage message, String contentType) throws Exception {
		final boolean isMultipart = contentType != null && contentType.startsWith("multipart/");
		if (isMultipart) {
			MimeMultipart mmp = new MimeMultipart(new ByteArrayDataSource(message.getBodyAsInputStream(null), contentType));
			String start = getValueFromHttpHeader(contentType, HTTP_HEADER_CONTENT_TYPE_PARAMETER_START);
			String soapAction = getValueFromHttpHeader(contentType, HTTP_HEADER_CONTENT_TYPE_PARAMETER_ACTION);
			if (soapAction != null) {
				message.putHeader(HTTP_HEADER_SOAP_ACTION, soapAction);
			}
			for (int i = 0; i < mmp.getCount(); i++) {
				MimeBodyPart bodyPart = (MimeBodyPart) mmp.getBodyPart(i);
				String cid = bodyPart.getContentID();
				if (start == null && i == 0 || start != null && start.equals(cid)) {
					for (Enumeration<Header> allHeaders = bodyPart.getAllHeaders(); allHeaders.hasMoreElements();) {
						Header header = allHeaders.nextElement();
						message.putHeader(header.getName(), header.getValue());
					}
					String charset = getValueFromHttpHeader(bodyPart.getContentType(), HTTP_HEADER_CONTENT_TYPE_PARAMETER_CHARSET);
					message.reset(BodyType.INPUT_STREAM, bodyPart.getInputStream(), charset);
				} else if (cid != null) {
					message.addAttachment(cid, bodyPart);
				} else {
					message.putVariable(getDispositionName(bodyPart), bodyPart.getContent());
				}
			}
		}
		return isMultipart;
	}

	public static String guessContentTypeFromName(String filename) {
		return java.net.URLConnection.guessContentTypeFromName(filename);
	}

	/**
	 * This is very rudimentary, but using http://tika.apache.org/ is overdone.
	 */
	public static String getFileExtension(String contentType) {
		if (isSOAP11(contentType) || isSOAP12(contentType)) {
			return "xml";
		}
		if (contentType.startsWith(APPLICATION)) {
			String ext = contentType.substring(APPLICATION.length());
			return "json".equals(ext) ? ext : "bin";
		}
		if (contentType.startsWith(TEXT)) {
			String ext = contentType.substring(TEXT.length());
			return "plain".equals(ext) ? "txt" : ext;
		}
		return null;
	}

}
