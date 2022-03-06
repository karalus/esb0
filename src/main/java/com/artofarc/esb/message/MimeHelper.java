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

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Map.Entry;

import javax.activation.DataSource;
import javax.mail.Header;
import javax.mail.MessagingException;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMultipart;

import com.artofarc.esb.context.Context;
import static com.artofarc.esb.http.HttpConstants.*;
import com.artofarc.util.ByteArrayOutputStream;

public final class MimeHelper {

	private static final String ROOTPART = "rootpart@artofarc.com";
	private static final String APPLICATION = "application/";
	private static final String TEXT = "text/";

	static MimeBodyPart createMimeBodyPart(String cid, String contentType, byte[] content, String name) throws MessagingException {
		InternetHeaders headers = new InternetHeaders();
		headers.setHeader(HTTP_HEADER_CONTENT_TYPE, contentType);
		MimeBodyPart part = new MimeBodyPart(headers, content);
		if (cid != null) {
			part.setContentID('<' + cid + '>');
		}
		if (name != null) {
			part.setDisposition(MimeBodyPart.ATTACHMENT + "; " + HTTP_HEADER_CONTENT_PARAMETER_NAME + '"' + name + '"');
		}
		return part;
	}

	public static String getDispositionName(MimeBodyPart bodyPart) throws MessagingException {
		return getValueFromHttpHeader(bodyPart.getHeader(HTTP_HEADER_CONTENT_DISPOSITION, null), HTTP_HEADER_CONTENT_PARAMETER_NAME);
	}

	public static MimeMultipart createMimeMultipart(Context context, ESBMessage message, String multipartContentType, ByteArrayOutputStream bos) throws Exception {
		if (bos == null) {
			bos = new ByteArrayOutputStream();
			message.writeTo(bos, context);
			message.closeBody();
		}
		return createMimeMultipart(context, message, multipartContentType, bos.toByteArray(), true, true);
	}

	/**
	 * @param multipartContentType e.g. "application/xop+xml"
	 */
	public static MimeMultipart createMimeMultipart(Context context, ESBMessage message, String multipartContentType, byte[] body, boolean withHeaders, boolean withAttachments) throws Exception {
		String contentType = message.removeHeader(HTTP_HEADER_CONTENT_TYPE);
		if (contentType == null) {
			throw new NullPointerException("Content-Type is null");
		}
		MimeMultipart mmp = new MimeMultipart("related; " + HTTP_HEADER_CONTENT_TYPE_PARAMETER_TYPE + '"' + multipartContentType + "\"; " + HTTP_HEADER_CONTENT_TYPE_PARAMETER_START
				+ "\"<" + ROOTPART + ">\"; " + HTTP_HEADER_CONTENT_TYPE_PARAMETER_START_INFO + '"' + contentType + '"');
		if (!multipartContentType.equals(contentType)) {
			contentType = multipartContentType + "; " + HTTP_HEADER_CONTENT_TYPE_PARAMETER_TYPE + '"' + contentType + '"';
		}
		MimeBodyPart part = createMimeBodyPart(ROOTPART, contentType + "; " + HTTP_HEADER_CONTENT_TYPE_PARAMETER_CHARSET + message.getSinkEncoding(), body, null);
		if (withHeaders) {
			for (Entry<String, Object> entry : message.getHeaders()) {
				part.setHeader(entry.getKey(), entry.getValue().toString());
			}
		}
		mmp.addBodyPart(part);
		if (withAttachments) {
			for (Iterator<MimeBodyPart> iter = message.getAttachments().values().iterator(); iter.hasNext();) {
				MimeBodyPart bodyPart = iter.next();
				mmp.addBodyPart(bodyPart);
				iter.remove();
			}
		}
		return mmp;
	}

	public static boolean parseMultipart(ESBMessage message, String contentType) throws Exception {
		final boolean isMultipart = contentType != null && contentType.startsWith("multipart/");
		if (isMultipart) {
			InputStream inputStream = message.getBodyAsInputStream(null);
			MimeMultipart mmp = new MimeMultipart(new DataSource() {
				
				@Override
				public InputStream getInputStream() {
					return inputStream;
				}
				
				@Override
				public OutputStream getOutputStream() {
					throw new UnsupportedOperationException();
				}
				
				@Override
				public String getContentType() {
					return contentType;
				}

				@Override
				public String getName() {
					return null;
				}
			});
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
					// remove angle brackets (https://tools.ietf.org/html/rfc2392)
					message.addAttachment(cid.substring(1, cid.length() - 1), bodyPart);
				} else {
					String dispositionName = getDispositionName(bodyPart);
					Object content = bodyPart.getContent();
					if (content instanceof String) {
						message.putVariable(dispositionName, content);
					} else {
						cid = getFilename(bodyPart.getHeader(HTTP_HEADER_CONTENT_DISPOSITION, null));
						bodyPart.setContentID('<' + cid + '>');
						message.addAttachment(cid, bodyPart);
					}
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
		if (!isNotXML(contentType)) {
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
