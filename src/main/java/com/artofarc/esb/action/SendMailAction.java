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
package com.artofarc.esb.action;

import java.util.Iterator;
import java.util.Properties;

import javax.activation.DataHandler;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.MimePartDataSource;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.WorkerPool;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.MimeHelper;
import com.artofarc.esb.resource.MailSessionFactory;

public class SendMailAction extends TerminalAction {

	private final Session _session;
	private final String from, to, cc, bcc, replyTo, subject, text, type;

	public SendMailAction(GlobalContext globalContext, Properties properties, String workerPoolName, String from, String to, String cc, String bcc, String replyTo, String subject, String text, String type, String userName, String password) {
		WorkerPool workerPool = globalContext.getWorkerPool(workerPoolName);
		properties.put("mail.event.executor", workerPool.getExecutorService());
		MailSessionFactory mailSessionFactory = globalContext.getResourceFactory(MailSessionFactory.class);
		Authenticator authenticator = userName == null ? null : new Authenticator() {

			@Override
			protected PasswordAuthentication getPasswordAuthentication() {
				return new PasswordAuthentication(userName, password);
			}
		};
		_session = mailSessionFactory.getResource(properties, authenticator).getSession();
		this.from = from;
		this.to = to;
		this.cc = cc;
		this.bcc = bcc;
		this.replyTo = replyTo;
		this.subject = subject;
		this.text = text;
		this.type = type;
	}

	@Override
	protected void execute(Context context, ESBMessage message) throws Exception {
		MimeMessage msg = new MimeMessage(_session);
		msg.setFrom(new InternetAddress((String) eval(from, context, message)));
		msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse((String) eval(to, context, message), false));
		if (cc != null) {
			msg.setRecipients(Message.RecipientType.CC, InternetAddress.parse((String) eval(cc, context, message), false));
		}
		if (bcc != null) {
			msg.setRecipients(Message.RecipientType.BCC, InternetAddress.parse((String) eval(bcc, context, message), false));
		}
		if (replyTo != null) {
			msg.setReplyTo(InternetAddress.parse((String) eval(replyTo, context, message), false));
		}
		msg.setSubject((String) eval(subject, context, message));
		String content = (String) eval(text, context, message);
		String contentType = eval(type, context, message) + "; " + HttpConstants.HTTP_HEADER_CONTENT_TYPE_PARAMETER_CHARSET + ESBMessage.CHARSET_DEFAULT;
		if (message.getAttachments().isEmpty()) {
			msg.setContent(content, contentType);
		} else {
			MimeMultipart mmp = MimeHelper.createMimeMultipart(context, message, "mixed", contentType, content.getBytes(ESBMessage.CHARSET_DEFAULT), false, false);
			for (Iterator<MimeBodyPart> iter = message.getAttachments().values().iterator(); iter.hasNext();) {
				MimeBodyPart bodyPart = iter.next();
				MimeBodyPart att = new MimeBodyPart(); 
				att.setDataHandler(new DataHandler(new MimePartDataSource(bodyPart)));
				att.setContentID(bodyPart.getContentID());
				att.setFileName(MimeHelper.getDispositionName(bodyPart)); 
				mmp.addBodyPart(att);
				iter.remove();
			}
			msg.setContent(mmp);
		}
		Transport.send(msg);
	}

}
