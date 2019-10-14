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
package com.artofarc.esb.servlet;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

import javax.mail.Header;
import javax.mail.MessagingException;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import static com.artofarc.esb.http.HttpConstants.*;

import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import static com.artofarc.esb.message.ESBConstants.*;

/**
 * Servlet implementation class GenericHttpListener
 */
@WebServlet(asyncSupported = true, urlPatterns = { "/*", "/admin/ext/*", "/admin/deploy/*" })
@MultipartConfig
public class GenericHttpListener extends HttpServlet {

	private static final long serialVersionUID = 1L;

	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
		// process input
		final String pathInfo = request.getRequestURI().substring(request.getContextPath().length());
		GlobalContext globalContext = (GlobalContext) getServletContext().getAttribute(ESBServletContextListener.CONTEXT);
		HttpConsumer consumerPort = globalContext.getHttpService(pathInfo);
		if (consumerPort != null) {
			if (consumerPort.isEnabled()) {
				try {
					Context context = consumerPort.getContextPool().getContext();
					if (context != null) {
						try {
							consumerPort.process(context, createESBMessage(request, pathInfo, consumerPort));
						} finally {
							consumerPort.getContextPool().releaseContext(context);
						}
					} else {
						response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ConsumerPort resource limit exceeded");
					}
				} catch (Exception e) {
					sendErrorResponse(response, e);
				}
			} else {
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ConsumerPort is disabled");
			}
		} else {
			response.sendError(HttpServletResponse.SC_NOT_FOUND, "No ConsumerPort registered");
		}
	}

	private static ESBMessage createESBMessage(HttpServletRequest request, String pathInfo, HttpConsumer consumerPort) throws IOException, MessagingException {
		// https://stackoverflow.com/questions/16339198/which-http-methods-require-a-body
		final boolean bodyPresent = request.getHeader(HTTP_HEADER_CONTENT_LENGTH) != null || request.getHeader(HTTP_HEADER_TRANSFER_ENCODING) != null;
		ESBMessage message = bodyPresent ? new ESBMessage(BodyType.INPUT_STREAM, request.getInputStream()) : new ESBMessage(BodyType.INVALID, null);
		message.getVariables().put(HttpMethod, request.getMethod());
		message.getVariables().put(ContextPath, request.getContextPath());
		message.getVariables().put(PathInfo, pathInfo);
		if (consumerPort.getBindPath().endsWith("*")) {
			message.getVariables().put(appendHttpUrlPath, URLDecoder.decode(pathInfo.substring(consumerPort.getBindPath().length() - 1), "UTF-8"));
		}
		message.putVariable(QueryString, request.getQueryString());
		message.setCharset(request.getCharacterEncoding());
		for (Enumeration<String> headerNames = request.getHeaderNames(); headerNames.hasMoreElements();) {
			String headerName = headerNames.nextElement();
			message.getHeaders().put(headerName, request.getHeader(headerName));
		}
		message.putVariable(RemoteUser, request.getRemoteUser());
		final X509Certificate[] certs = (X509Certificate[]) request.getAttribute("javax.servlet.request.X509Certificate");
		if (certs != null) {
			// Only for SSL mutual authentication
			// https://stackoverflow.com/questions/24351472/getattributejavax-servlet-request-x509certificate-not-set-spring-cxf-jetty
			message.getVariables().put(ClientCertificate, certs[0]);
		}
		if (bodyPresent) {
			parseAttachments(request.getContentType(), message);
		}
		// copy into variable for HttpServletResponseAction
		message.putVariable(HTTP_HEADER_ACCEPT_CHARSET, message.removeHeader(HTTP_HEADER_ACCEPT_CHARSET));
		message.putVariable(HTTP_HEADER_ACCEPT_ENCODING, message.removeHeader(HTTP_HEADER_ACCEPT_ENCODING));
		message.putVariable(HTTP_HEADER_ACCEPT, message.removeHeader(HTTP_HEADER_ACCEPT));
		message.getVariables().put(AsyncContext, request.startAsync());
		return message;
	}

	private static void parseAttachments(String contentType, ESBMessage message) throws IOException, MessagingException {
		if (contentType != null && contentType.startsWith("multipart/")) {
			MimeMultipart mmp = new MimeMultipart(new ByteArrayDataSource(message.getUncompressedInputStream(), contentType));
			String start = removeQuotes(getValueFromHttpHeader(contentType, HTTP_HEADER_CONTENT_TYPE_PARAMETER_START));
			message.putHeader(HTTP_HEADER_SOAP_ACTION, getValueFromHttpHeader(contentType, HTTP_HEADER_CONTENT_TYPE_PARAMETER_ACTION));
			for (int i = 0; i < mmp.getCount(); i++) {
				MimeBodyPart bodyPart = (MimeBodyPart) mmp.getBodyPart(i);
				if (start == null && i == 0 || start != null && start.equals(bodyPart.getContentID())) {
					for (@SuppressWarnings("unchecked")
					Enumeration<Header> allHeaders = bodyPart.getAllHeaders(); allHeaders.hasMoreElements();) {
						Header header = allHeaders.nextElement();
						message.putHeader(header.getName(), header.getValue());
					}
					message.reset(BodyType.INPUT_STREAM, bodyPart.getInputStream());
				} else {
					message.addAttachment(bodyPart);
				}
			}
		}
	}

	public static void sendErrorResponse(HttpServletResponse response, Exception e) throws IOException {
		int httpRetCode = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
		if (e instanceof TimeoutException || e instanceof SocketTimeoutException) {
			httpRetCode = HttpServletResponse.SC_GATEWAY_TIMEOUT;
		} else if (e instanceof RejectedExecutionException) {
			httpRetCode = HttpServletResponse.SC_SERVICE_UNAVAILABLE;
		}
		response.sendError(httpRetCode, e.getMessage());
	}

}
