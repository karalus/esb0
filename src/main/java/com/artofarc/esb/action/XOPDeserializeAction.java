/*
 * Copyright 2022 Andre Karalus
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

/**
 * Deserialize XOP package.
 *   
 * @see <a href="https://www.w3.org/TR/xop10/">XOP</a>
 */
public class XOPDeserializeAction extends TransformAction {

	public XOPDeserializeAction() {
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
				"declare variable $attachments as document-node() external;" +
				"local:copy-xop(if (. instance of element()) then . else *, $attachments/*)");
	}

}
