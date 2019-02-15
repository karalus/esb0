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
package com.artofarc.esb.action;

import java.util.Iterator;

import javax.xml.bind.DatatypeConverter;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.StreamUtils;

/**
 * Deserialize XOP package. This implementation is memory exhaustive and should be optimized by using stream decoder.
 *   
 * @see <a href="https://www.w3.org/TR/xop10/">XOP</a>
 */
public class MtomXopDeserializeAction extends TransformAction {

	public MtomXopDeserializeAction() {
		super("declare function local:copy-xop($element as element(), $attachments as element()) as element() {\n" +
					"element {node-name($element)}\n" +
						"{ $element/@*,\n" +
						"for $child in $element/node()\n" +
							"return if ($child instance of element())\n" +
								"then if ($child/local-name() = 'Include' and $child/namespace-uri() = 'http://www.w3.org/2004/08/xop/include')\n" +
									"then $attachments/*[@cid=$child/@href]/text()\n" +
									"else local:copy-xop($child, $attachments)\n" +
								"else $child\n" +
						"}\n" +
				"};\n" +
				"declare variable $attachments as element() external;" +
				"local:copy-xop(., $attachments)"); 
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		Document document = context.getDocumentBuilder().newDocument();
		Node rootNode = document.appendChild(document.createElement("attachments"));
		for (Iterator<String> iter = message.getAttachments().keySet().iterator(); iter.hasNext();) {
			String cid = iter.next();
			Element attachment = (Element) rootNode.appendChild(document.createElement("attachment"));
			attachment.setAttribute("cid", "cid:" + cid);
			byte[] ba = StreamUtils.copy(message.getAttachments().get(cid).getInputStream());
			iter.remove();
			attachment.setTextContent(DatatypeConverter.printBase64Binary(ba));
		}
		message.putVariable("attachments", rootNode);
		return super.prepare(context, message, inPipeline);
	}

}
