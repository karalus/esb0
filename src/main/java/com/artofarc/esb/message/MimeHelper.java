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
import java.util.StringTokenizer;

import javax.activation.DataSource;
import javax.mail.Header;
import javax.mail.MessagingException;
import javax.mail.internet.ContentDisposition;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMultipart;

import com.artofarc.esb.context.Context;
import static com.artofarc.esb.http.HttpConstants.*;
import com.artofarc.util.ByteArrayOutputStream;
import com.artofarc.util.URLUtils;

public final class MimeHelper {

	private static final boolean FORCE_MIMEMULTIPART = Boolean.parseBoolean(System.getProperty("esb0.forceMimeMultipart"));
	private static final String ROOTPART = "rootpart@artofarc.com";
	private static final String START_ROOTPART = "\"; " + HTTP_HEADER_CONTENT_TYPE_PARAMETER_START + "\"<" + ROOTPART + ">\"";

	private static final Evaluator<Exception> evaluator = new Evaluator<Exception>() {

		@Override
		protected Exception createException(String message) {
			return new IllegalArgumentException(message);
		}
	};

	static MimeBodyPart createMimeBodyPart(String cid, String contentType, byte[] content, String name) throws MessagingException {
		InternetHeaders headers = new InternetHeaders();
		headers.setHeader(HTTP_HEADER_CONTENT_TYPE, contentType);
		MimeBodyPart part = new MimeBodyPart(headers, content);
		if (cid != null) {
			part.setContentID('<' + cid + '>');
		}
		if (name != null) {
			setDisposition(part, MimeBodyPart.ATTACHMENT, name);
		}
		return part;
	}

	private static void setDisposition(MimeBodyPart bodyPart, String type, String name) throws MessagingException {
		bodyPart.setDisposition(type + "; " + HTTP_HEADER_CONTENT_PARAMETER_NAME + '"' + name + '"');
	}

	public static String getDispositionName(MimeBodyPart bodyPart) throws MessagingException {
		return getValueFromHttpHeader(bodyPart.getHeader(HTTP_HEADER_CONTENT_DISPOSITION, null), HTTP_HEADER_CONTENT_PARAMETER_NAME);
	}

	public static boolean isMimeMultipart(String multipartSubtype, ESBMessage message) {
		return multipartSubtype != null && (FORCE_MIMEMULTIPART || multipartSubtype == "form-data" || message.getAttachments().size() > 0);
	}

	public static MimeMultipart createMimeMultipart(Context context, ESBMessage message, String multipartSubtype, String multipartContentType, ByteArrayOutputStream bos) throws Exception {
		byte[] body = bos != null ? bos.toByteArray() : message.getBodyAsByteArray(context);
		return createMimeMultipart(context, message, multipartSubtype, multipartContentType, body, true, true);
	}

	/**
	 * @param multipartContentType e.g. "application/xop+xml"
	 */
	public static MimeMultipart createMimeMultipart(Context context, ESBMessage message, String multipartSubtype, String multipartContentType, byte[] body, boolean withHeaders, boolean withAttachments) throws Exception {
		String contentType = message.removeHeader(HTTP_HEADER_CONTENT_TYPE);
		MimeMultipart mmp;
		MimeBodyPart part;
		if (multipartSubtype == "form-data") {
			if (multipartContentType == null) {
				throw new IllegalArgumentException("For multipart/form-data a list of form data is required");
			}
			mmp = new MimeMultipart(multipartSubtype);
			StringTokenizer tokenizer = new StringTokenizer(multipartContentType, ",");
			while (tokenizer.hasMoreTokens()) {
				String pair = tokenizer.nextToken();
				int i = pair.indexOf("=");
				if (i < 0) {
					throw new IllegalArgumentException("Delimiter '=' is missing: " + pair);
				}
				String name = pair.substring(0, i);
				String exp = pair.substring(i + 1);
				boolean isBody = "${body}".equals(exp);
				Object value = isBody ? message.getBodyAsByteArray(context) : evaluator.eval(exp, context, message);
				if (value instanceof MimeBodyPart) {
					part = (MimeBodyPart) value;
					part.setContentID(null);
					String disposition = part.getHeader(HTTP_HEADER_CONTENT_DISPOSITION, null);
					if (disposition != null) {
						ContentDisposition cd = new ContentDisposition(disposition);
						cd.setDisposition("form-data");
						cd.setParameter("name", name);
						part.setHeader(HTTP_HEADER_CONTENT_DISPOSITION, cd.toString());
					} else {
						setDisposition(part, "form-data", name);
					}
				} else {
					InternetHeaders headers = new InternetHeaders();
					if (isBody) {
						headers.setHeader(HTTP_HEADER_CONTENT_TYPE, contentType);
						for (Entry<String, Object> entry : message.getHeaders()) {
							headers.setHeader(entry.getKey(), entry.getValue().toString());
						}
					}
					part = new MimeBodyPart(headers, value instanceof byte[] ? (byte[]) value : value != null ? value.toString().getBytes(ESBMessage.CHARSET_DEFAULT) : null);
					setDisposition(part, "form-data", name);
				}
				mmp.addBodyPart(part);
			}
		} else {
			if (contentType == null) {
				throw new NullPointerException("Content-Type is null");
			}
			if (multipartSubtype == "related") {
				String mediaType = getValueFromHttpHeader(contentType);
				if (multipartContentType == null) {
					multipartContentType = mediaType;
				}
				multipartSubtype += "; " + HTTP_HEADER_CONTENT_TYPE_PARAMETER_TYPE + '"' + multipartContentType + START_ROOTPART;
				if (multipartContentType.equals(mediaType)) {
					multipartContentType = contentType;
				} else {
					if (mediaType != contentType) {
						// append params like action and charset
						multipartContentType += contentType.substring(mediaType.length());
					}
					multipartContentType += "; " + HTTP_HEADER_CONTENT_TYPE_PARAMETER_TYPE + '"' + mediaType + '"';
					multipartSubtype += "; " + HTTP_HEADER_CONTENT_TYPE_PARAMETER_START_INFO + '"' + mediaType + '"';
				}
				mmp = new MimeMultipart(multipartSubtype); 
				part = createMimeBodyPart(ROOTPART, multipartContentType, body, null);
			} else {
				mmp = new MimeMultipart(multipartSubtype);
				part = createMimeBodyPart(null, contentType, body, null);
			}
			if (withHeaders) {
				for (Entry<String, Object> entry : message.getHeaders()) {
					if (entry.getValue() != null) {
						part.setHeader(entry.getKey(), entry.getValue().toString());
					}
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
		}
		return mmp;
	}

	static String parseContentType(ESBMessage message, String contentType) throws Exception {
		if (contentType != null && contentType.startsWith("multipart/")) {
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
			String newContentType = null;
			for (int i = 0; i < mmp.getCount(); i++) {
				MimeBodyPart bodyPart = (MimeBodyPart) mmp.getBodyPart(i);
				String cid = bodyPart.getContentID();
				if (start == null && i == 0 || start != null && start.equals(cid)) {
					for (Enumeration<Header> allHeaders = bodyPart.getAllHeaders(); allHeaders.hasMoreElements();) {
						Header header = allHeaders.nextElement();
						message.putHeader(header.getName(), header.getValue());
					}
					newContentType = bodyPart.getContentType();
					message.reset(BodyType.INPUT_STREAM, bodyPart.getInputStream(), determineCharset(newContentType));
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
			return newContentType;
		} else if (HTTP_HEADER_CONTENT_TYPE_FORM_URLENCODED.equals(contentType)) {
			URLUtils.parseURLEncodedString(message.getBodyAsString(null), message.getVariables(), null);
			message.reset(BodyType.INVALID, null);
			return null;
		}
		return contentType;
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
		if (contentType.startsWith(MEDIATYPE_APPLICATION)) {
			String subtype = contentType.substring(MEDIATYPE_APPLICATION.length());
			return "json".equals(subtype) ? subtype : "bin";
		}
		if (contentType.startsWith(MEDIATYPE_TEXT)) {
			String subtype = contentType.substring(MEDIATYPE_TEXT.length());
			return "plain".equals(subtype) ? "txt" : subtype;
		}
		return null;
	}

}
