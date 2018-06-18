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
package com.artofarc.esb.resource;

import javax.xml.xquery.XQDataSource;

import net.sf.saxon.expr.XPathContext;
import net.sf.saxon.lib.ExtensionFunctionCall;
import net.sf.saxon.lib.ExtensionFunctionDefinition;
import net.sf.saxon.om.Sequence;
import net.sf.saxon.om.StructuredQName;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.type.BuiltInAtomicType;
import net.sf.saxon.value.Int64Value;
import net.sf.saxon.value.SequenceType;
import net.sf.saxon.value.StringValue;

import com.saxonica.xqj.SaxonXQDataSource;

public class XQDataSourceFactory {

	public static XQDataSource createXQDataSource() {
		SaxonXQDataSource dataSource = new SaxonXQDataSource();
		dataSource.getConfiguration().registerExtensionFunction(new UUID());
		dataSource.getConfiguration().registerExtensionFunction(new CurrentTimeMillis());
		return dataSource;
	}

	private static class UUID extends ExtensionFunctionDefinition {

		@Override
		public StructuredQName getFunctionQName() {
			return new StructuredQName("fn-artofarc", "http://artofarc.com/xpath-extension", "uuid");
		}

		@Override
		public SequenceType[] getArgumentTypes() {
			return new SequenceType[] {};
		}

		@Override
		public SequenceType getResultType(SequenceType[] suppliedArgumentTypes) {
			return SequenceType.SINGLE_STRING;
		}

		@Override
		public ExtensionFunctionCall makeCallExpression() {
			return new ExtensionFunctionCall() {

				@Override
				public Sequence call(XPathContext context, Sequence[] arguments) throws XPathException {
					return StringValue.makeStringValue(java.util.UUID.randomUUID().toString());
				}

			};
		}
	}

	private static class CurrentTimeMillis extends ExtensionFunctionDefinition {

		@Override
		public StructuredQName getFunctionQName() {
			return new StructuredQName("fn-artofarc", "http://artofarc.com/xpath-extension", "currentTimeMillis");
		}

		@Override
		public SequenceType[] getArgumentTypes() {
			return new SequenceType[] {};
		}

		@Override
		public SequenceType getResultType(SequenceType[] suppliedArgumentTypes) {
			return BuiltInAtomicType.LONG.one();
		}

		@Override
		public ExtensionFunctionCall makeCallExpression() {
			return new ExtensionFunctionCall() {

				@Override
				public Sequence call(XPathContext context, Sequence[] arguments) throws XPathException {
					return Int64Value.makeDerived(System.currentTimeMillis(), BuiltInAtomicType.LONG);
				}

			};
		}
	}

}
