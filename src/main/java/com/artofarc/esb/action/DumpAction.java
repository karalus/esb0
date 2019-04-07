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

import java.io.PrintStream;
import java.util.Iterator;
import java.util.Map;

import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Node;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;

public class DumpAction extends TerminalAction {

	@Override
	protected void execute(Context context, ExecutionContext resource, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		super.execute(context, resource, message, nextActionIsPipelineStop);
		System.out.println("Headers:");
		dumpMap(context, message.getHeaders(), System.out);
		System.out.println("Variables:");
		dumpMap(context, message.getVariables(), System.out);
		if (message.getBodyType() != BodyType.INVALID) {
			System.out.println("Body:");
			if (message.getBodyType() == BodyType.EXCEPTION) {
				message.<Exception> getBody().printStackTrace(System.out);
			} else if (message.isStream()) {
				System.out.println(message.getBodyAsString(context));
			} else {
				message.writeRawTo(System.out, context);
				System.out.println();
			}
			System.out.flush();
		}
	}

	public static void dumpMap(Context context, Map<String, Object> map, PrintStream ps) throws TransformerException {
		ps.print('{');
		for (Iterator<Map.Entry<String, Object>> iter = map.entrySet().iterator(); iter.hasNext();) {
			Map.Entry<String, Object> entry = iter.next();
			ps.print(entry.getKey() + "=");
			if (entry.getValue() instanceof Node) {
				context.getIdenticalTransformer().transform(new DOMSource((Node) entry.getValue()), new StreamResult(ps));
			} else {
				ps.print(entry.getValue());
			}
			if (iter.hasNext()) {
				ps.print(", ");
			}
		}
		ps.println('}');
	}

}
