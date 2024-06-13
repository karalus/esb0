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
package com.artofarc.esb.servlet;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

import javax.servlet.AsyncContext;
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
import static com.artofarc.esb.message.ESBConstants.*;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.DataStructures;

/**
 * This servlet is the one and only HTTP endpoint for all services.
 */
@WebServlet(asyncSupported = true, urlPatterns = { "/*", "/admin/ext/*", ESBServletContextListener.ADMIN_SERVLET_PATH + "/*" })
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
			String origin = request.getHeader("Origin");
			boolean preflight = origin != null && "OPTIONS".equals(request.getMethod());
			if (origin != null) {
				response.setHeader("Access-Control-Allow-Origin", origin);
				if (consumerPort.getRequiredRole() != null) {
					if (preflight) {
						response.setHeader("Access-Control-Allow-Headers", HTTP_HEADER_AUTHORIZATION);
					}
					response.setHeader("Access-Control-Allow-Credentials", "true");
				}
			}
			boolean secure = true;
			if (consumerPort.getRequiredRole() != null && !preflight) {
				secure = request.authenticate(response);
				if (secure && !request.isUserInRole(consumerPort.getRequiredRole())) {
					secure = false;
					sendError(response, HttpServletResponse.SC_FORBIDDEN, "User not in role " + consumerPort.getRequiredRole());
				}
			}
			if (secure) {
				if (consumerPort.isEnabled()) {
					Context context = consumerPort.acquireContext();
					if (context != null) {
						try {
							ESBMessage message = createESBMessage(context, request, pathInfo, consumerPort);
							AsyncContext asyncContext = request.startAsync();
							asyncContext.setTimeout(consumerPort.getAsyncTimeout());
							context.putResource(AsyncContext, asyncContext);
							try {
								consumerPort.processWithServletResponse(context, message);
							} catch (Exception e) {
								if (!response.isCommitted()) {
									response.reset();
									Number httpResponseCode = message.getVariable(HttpResponseCode, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
									sendError(response, httpResponseCode.intValue() < HttpServletResponse.SC_BAD_REQUEST ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : httpResponseCode.intValue(), e);
								}
								asyncContext.complete();
							} finally {
								context.removeResource(AsyncContext);
							}
						} catch (Exception e) {
							sendError(response, HttpServletResponse.SC_BAD_REQUEST, e);
						} finally {
							consumerPort.releaseContext(context);
						}
					} else {
						sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ConsumerPort resource limit exceeded");
					}
				} else {
					sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ConsumerPort is disabled");
				}
			}
		}
	}

	private static ESBMessage createESBMessage(Context context, HttpServletRequest request, String pathInfo, HttpConsumer httpConsumer) throws Exception {
		// https://stackoverflow.com/questions/16339198/which-http-methods-require-a-body
		final boolean bodyPresent = request.getHeader(HTTP_HEADER_CONTENT_LENGTH) != null || request.getHeader(HTTP_HEADER_TRANSFER_ENCODING) != null;
		final ESBMessage message = bodyPresent ? new ESBMessage(BodyType.INPUT_STREAM, request.getInputStream()) : new ESBMessage(BodyType.INVALID, null);
		message.getVariables().put(RemoteAddr, request.getRemoteAddr());
		final String methodOverride = request.getHeader(HTTP_HEADER_X_METHOD_OVERRIDE);
		message.getVariables().put(HttpMethod, methodOverride != null ? methodOverride : request.getMethod());
		message.getVariables().put(ContextPath, request.getContextPath());
		message.getVariables().put(PathInfo, pathInfo);
		if (httpConsumer.isPathMapping()) {
			message.getVariables().put(appendHttpUrlPath, pathInfo.substring(httpConsumer.getBindPath().length()));
		}
		message.putVariableIfNotNull(QueryString, request.getQueryString());
		for (Enumeration<String> headerNames = request.getHeaderNames(); headerNames.hasMoreElements();) {
			String headerName = headerNames.nextElement();
			message.putHeader(headerName, request.getHeader(headerName));
		}
		message.putVariableIfNotNull(RemoteUser, request.getRemoteUser());
		final X509Certificate[] certs = (X509Certificate[]) request.getAttribute("javax.servlet.request.X509Certificate");
		if (certs != null) {
			// Only for SSL mutual authentication
			// https://stackoverflow.com/questions/24351472/getattributejavax-servlet-request-x509certificate-not-set-spring-cxf-jetty
			message.getVariables().put(ClientCertificate, certs[0]);
		}
		if (bodyPresent) {
			if (httpConsumer.getOverwriteContentType() != null) {
				message.putHeader(HTTP_HEADER_CONTENT_TYPE, httpConsumer.getOverwriteContentType());
			}
			message.prepareContent(context);
		}
		// copy into variable for HttpServletResponseAction
		message.putVariableIfNotNull(HTTP_HEADER_ACCEPT_CHARSET, message.removeHeader(HTTP_HEADER_ACCEPT_CHARSET));
		message.putVariableIfNotNull(HTTP_HEADER_ACCEPT_ENCODING, message.removeHeader(HTTP_HEADER_ACCEPT_ENCODING));
		message.putVariableIfNotNull(HTTP_HEADER_ACCEPT, message.removeHeader(HTTP_HEADER_ACCEPT));
		return message;
	}

	static void sendError(HttpServletResponse response, int sc, Exception e) throws IOException {
		if (e instanceof TimeoutException || e instanceof SocketTimeoutException) {
			sc = HttpServletResponse.SC_GATEWAY_TIMEOUT;
		} else if (e instanceof RejectedExecutionException) {
			sc = HttpServletResponse.SC_SERVICE_UNAVAILABLE;
		} else if (e instanceof FileNotFoundException) {
			sc = HttpServletResponse.SC_NOT_FOUND;
		}
		response.setStatus(sc);
		response.setContentType(HTTP_HEADER_CONTENT_TYPE_XML);
		response.getWriter().print(DataStructures.asXMLString(e));
	}

	static void sendError(HttpServletResponse response, int sc, String message) throws IOException {
		response.setStatus(sc);
		response.setContentType(HTTP_HEADER_CONTENT_TYPE_XML);
		response.getWriter().print("<message>" + message + "</message>");
	}

}
