/*
 * Copyright 2023 Andre Karalus
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

import java.io.IOException;

import javax.websocket.CloseReason;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GenericWebsocketListener extends Endpoint {

	protected final static Logger logger = LoggerFactory.getLogger(GenericWebsocketListener.class);

	public GenericWebsocketListener() {
	}

	@Override
	public void onOpen(Session session, EndpointConfig config) {
		logger.info("ID" + session.getId());
		RemoteEndpoint.Basic remote = session.getBasicRemote();
		session.addMessageHandler(String.class, new MessageHandler.Whole<String>() {
			public void onMessage(String text) {
				try {
					remote.sendText("Got your message (" + text + "). Thanks!");
				} catch (IOException ioe) {
					logger.info("send failure", ioe);
				}
			}
		});
	}

	@Override
	public void onClose(Session session, CloseReason closeReason) {
		logger.info("ID" + session.getId());
		logger.info("CloseReason" + closeReason);
	}

	@Override
	public void onError(Session session, Throwable thr) {
		logger.info("ID" + session.getId(), thr);
	}

}
