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
import java.util.Enumeration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

import javax.mail.BodyPart;
import javax.mail.Header;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;
import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.PoolContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.ESBVariableConstants;

/**
 * Servlet implementation class GenericHttpListener
 */
@WebServlet(asyncSupported=true, urlPatterns={"/*", "/admin/ext/*"})
@MultipartConfig
public class GenericHttpListener extends HttpServlet {

	private static final long serialVersionUID = 1L;

	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		// process input
		final String pathInfo = request.getRequestURI().substring(request.getContextPath().length());
		log("Incoming HTTP request with uri " + request.getRequestURI());
		PoolContext poolContext = (PoolContext) getServletContext().getAttribute(ESBServletContextListener.POOL_CONTEXT);
		HttpConsumer consumerPort = poolContext.getGlobalContext().getHttpService(pathInfo);
		if (consumerPort != null) {
			if (consumerPort.isEnabled()) {
				// https://stackoverflow.com/questions/16339198/which-http-methods-require-a-body
				final boolean bodyPresent = request.getHeader(HttpConstants.HTTP_HEADER_CONTENT_LENGTH) != null
						|| request.getHeader(HttpConstants.HTTP_HEADER_TRANSFER_ENCODING) != null;
				ESBMessage message = bodyPresent ? new ESBMessage(BodyType.INPUT_STREAM, request.getInputStream()) : new ESBMessage(BodyType.INVALID, null);
				message.getVariables().put(ESBVariableConstants.HttpMethod, request.getMethod());
				message.getVariables().put(ESBVariableConstants.ContextPath, request.getContextPath());
				message.getVariables().put(ESBVariableConstants.PathInfo, pathInfo);
				if (consumerPort.getBindPath().endsWith("*")) {
					message.getVariables().put(ESBVariableConstants.appendHttpUrlPath, pathInfo.substring(consumerPort.getBindPath().length() - 1));
				}
				message.getVariables().put(ESBVariableConstants.QueryString, request.getQueryString());
				message.setCharsetName(request.getCharacterEncoding());
				for (Enumeration<String> headerNames = request.getHeaderNames(); headerNames.hasMoreElements();) {
					String headerName = headerNames.nextElement();
					message.getHeaders().put(headerName, request.getHeader(headerName));
				}
				if (bodyPresent) {
					final String contentType = request.getContentType();
					if (contentType.startsWith("multipart/")) {
						try {
							MimeMultipart mmp = new MimeMultipart(new ByteArrayDataSource(message.getUncompressedInputStream(), contentType));
							for (int i = 0; i < mmp.getCount(); i++) {
								BodyPart bodyPart = mmp.getBodyPart(i);
								if (i == 0) {
									for (@SuppressWarnings("unchecked")
									Enumeration<Header> allHeaders = bodyPart.getAllHeaders(); allHeaders.hasMoreElements();) {
										final Header header = allHeaders.nextElement();
										message.putHeader(header.getName(), header.getValue());
									}
									message.reset(BodyType.INPUT_STREAM, bodyPart.getInputStream());
								} else {
									message.addAttachment(bodyPart);
								}
							}
						} catch (MessagingException e) {
							sendErrorResponse(response, e);
						}
					}
				}
				message.getVariables().put(ESBVariableConstants.AsyncContext, request.startAsync());
				// process message
				Context context = null;
				try {
					context = consumerPort.getContext(poolContext);
				} catch (Exception e) {
					sendErrorResponse(response, e);
				}
				if (context != null) {
					try {
						consumerPort.process(context, message);
					} catch (Exception e) {
						sendErrorResponse(response, e);
					} finally {
						consumerPort.releaseContext(context);
					}
				} else {
					response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ConsumerPort resource limit exceeded");
				}
			} else {
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ConsumerPort is disabled");
			}
		} else {
			response.sendError(HttpServletResponse.SC_NOT_FOUND, "No ConsumerPort registered");
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
