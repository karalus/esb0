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
package com.artofarc.esb;

import java.util.ArrayDeque;

import javax.xml.namespace.QName;

import org.xml.sax.SAXException;

import com.artofarc.util.XSOMHelper;
import com.sun.xml.xsom.XSElementDecl;
import com.sun.xml.xsom.XSSchemaSet;
import com.sun.xml.xsom.XSSimpleType;
import com.sun.xml.xsom.XSTerm;

public final class XmlSampleGenerator {

	private final ArrayDeque<QName> stack = new ArrayDeque<>();
	private final XSOMHelper xsomHelper;

	public XmlSampleGenerator(XSSchemaSet schemaSet, String rootElement) {
		QName _rootElement = QName.valueOf(rootElement);
		startELement(_rootElement);
		System.out.println();
		stack.push(_rootElement);
		XSElementDecl element = schemaSet.getElementDecl(_rootElement.getNamespaceURI(), _rootElement.getLocalPart());
		xsomHelper = new XSOMHelper(element.getType().asComplexType());
	}

	private void startELement(QName element) {
		System.out.print("<" + element.getLocalPart() + " xmlns='" + element.getNamespaceURI() + "'>");
	}

	private void endELement(QName element) {
		System.out.println("</" + element.getLocalPart() + ">");
	}

	public void go() throws SAXException {
		for(;;) {
			XSTerm term = xsomHelper.matchElement(null, null);
			int level = xsomHelper.getLevel();
			while (level < stack.size()) {
				QName element = stack.pop();
				endELement(element);
			}
			if (term == null) break;
			if (!xsomHelper.isLastElementRequired()) {
				System.out.println("<!-- Optional -->");
			}
			if (xsomHelper.isLastElementAny()) {
				System.out.println("<!-- Any -->");
				xsomHelper.endAny();
			} else {
				if (xsomHelper.isStartArray()) {
					while(xsomHelper.endArray());
					xsomHelper.endArray();
					System.out.println("<!-- Repeated -->");
				} 
				QName element = new QName(term.apply(XSOMHelper.GetNamespace), term.apply(XSOMHelper.GetName));
				startELement(element);
				XSSimpleType simpleType = xsomHelper.getSimpleType();
				if (simpleType != null) {
					System.out.print("42");
					endELement(element);
				} else {
					System.out.println();
					stack.push(element);
				}
			}
		}
	}

}
