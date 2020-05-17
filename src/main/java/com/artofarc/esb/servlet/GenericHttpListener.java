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

import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.artofarc.esb.Registry;
import com.artofarc.esb.context.Context;
import static com.artofarc.esb.http.HttpConstants.*;

import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.MimeHelper;

import static com.artofarc.esb.message.ESBConstants.*;

/**
 * This servlet is the one and only HTTP endpoint for all services.
 */
@WebServlet(asyncSupported = true, urlPatterns = { "/*", "/admin/ext/*", "/" + ESBServletContextListener.ADMIN_SERVLET_PATH + "/*" })
@MultipartConfig
public class GenericHttpListener extends HttpServlet {

	private static final long serialVersionUID = 1L;

	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		// process input
		final String pathInfo = request.getRequestURI().substring(request.getContextPath().length());
		Registry registry = (Registry) getServletContext().getAttribute(ESBServletContextListener.CONTEXT);
		HttpConsumer consumerPort = registry.getHttpService(pathInfo);
		if (consumerPort == null) {
			sendError(response, HttpServletResponse.SC_NOT_FOUND, "No ConsumerPort registered");
		} else {
			boolean secure = true;
			if (consumerPort.getRequiredRole() != null) {
				secure = request.authenticate(response);
				if (secure && !request.isUserInRole(consumerPort.getRequiredRole())) {
					secure = false;
					sendError(response, HttpServletResponse.SC_FORBIDDEN, "User not in role " + consumerPort.getRequiredRole());
				}
			}
			if (secure) {
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
							sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ConsumerPort resource limit exceeded");
						}
					} catch (Exception e) {
						sendError(response, e);
					}
				} else {
					sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ConsumerPort is disabled");
				}
			}
		}
	}

	private static ESBMessage createESBMessage(HttpServletRequest request, String pathInfo, HttpConsumer httpConsumer) throws Exception {
		// https://stackoverflow.com/questions/16339198/which-http-methods-require-a-body
		final boolean bodyPresent = request.getHeader(HTTP_HEADER_CONTENT_LENGTH) != null || request.getHeader(HTTP_HEADER_TRANSFER_ENCODING) != null;
		final ESBMessage message = bodyPresent ? new ESBMessage(BodyType.INPUT_STREAM, request.getInputStream()) : new ESBMessage(BodyType.INVALID, null);
		final String methodOverride = request.getHeader(HTTP_HEADER_X_METHOD_OVERRIDE);
		message.getVariables().put(HttpMethod, methodOverride != null ? methodOverride : request.getMethod());
		message.getVariables().put(ContextPath, request.getContextPath());
		message.getVariables().put(PathInfo, pathInfo);
		if (httpConsumer.isPathMapping()) {
			message.getVariables().put(appendHttpUrlPath, URLDecoder.decode(pathInfo.substring(httpConsumer.getBindPath().length()), "UTF-8"));
		}
		message.putVariable(QueryString, request.getQueryString());
		message.setCharset(request.getCharacterEncoding());
		for (Enumeration<String> headerNames = request.getHeaderNames(); headerNames.hasMoreElements();) {
			String headerName = headerNames.nextElement();
			message.putHeader(headerName, request.getHeader(headerName));
		}
		message.putVariable(RemoteUser, request.getRemoteUser());
		final X509Certificate[] certs = (X509Certificate[]) request.getAttribute("javax.servlet.request.X509Certificate");
		if (certs != null) {
			// Only for SSL mutual authentication
			// https://stackoverflow.com/questions/24351472/getattributejavax-servlet-request-x509certificate-not-set-spring-cxf-jetty
			message.getVariables().put(ClientCertificate, certs[0]);
		}
		if (bodyPresent) {
			MimeHelper.parseMultipart(message, request.getContentType());
		}
		// copy into variable for HttpServletResponseAction
		message.putVariable(HTTP_HEADER_ACCEPT_CHARSET, message.removeHeader(HTTP_HEADER_ACCEPT_CHARSET));
		message.putVariable(HTTP_HEADER_ACCEPT_ENCODING, message.removeHeader(HTTP_HEADER_ACCEPT_ENCODING));
		message.putVariable(HTTP_HEADER_ACCEPT, message.removeHeader(HTTP_HEADER_ACCEPT));
		message.getVariables().put(AsyncContext, request.startAsync());
		return message;
	}

	public static void sendError(HttpServletResponse response, Exception e) throws IOException {
		int httpRetCode = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
		if (e instanceof TimeoutException || e instanceof SocketTimeoutException) {
			httpRetCode = HttpServletResponse.SC_GATEWAY_TIMEOUT;
		} else if (e instanceof RejectedExecutionException) {
			httpRetCode = HttpServletResponse.SC_SERVICE_UNAVAILABLE;
		}
		response.setStatus(httpRetCode);
		response.setContentType(SOAP_1_1_CONTENT_TYPE);
		response.getWriter().print(ESBMessage.asXMLString(e));
	}

	public static void sendError(HttpServletResponse response, int sc, String message) throws IOException {
		response.setStatus(sc);
		response.setContentType(SOAP_1_1_CONTENT_TYPE);
		response.getWriter().print("<message>" + message + "</message>");
	}

}
