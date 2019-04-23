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

import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;

import javax.xml.transform.Source;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.xquery.XQException;
import javax.xml.xquery.XQItem;
import javax.xml.xquery.XQSequence;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLFilterImpl;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.StringWriter;

public abstract class SAXAction extends Action {

	static class FeatureFilter extends XMLFilterImpl {

		FeatureFilter() {
		}

		FeatureFilter(XMLReader parent) {
			super(parent);
		}

		@Override
		public void setFeature(String name, boolean value) {
		}
	}

	static class XQJFilter extends FeatureFilter {
		private final XQItem _item;

		XQJFilter(XQItem item) {
			_item = item;
		}

		@Override
		public void parse(InputSource source) throws SAXException {
			try {
				_item.writeItemToResult(new SAXResult(getContentHandler()));
			} catch (XQException e) {
				throw new SAXException(e);
			}
		}
	}

	public static StreamSource inputSourceToSource(InputSource inputSource) throws UnsupportedEncodingException {
		StreamSource streamSource = new StreamSource(inputSource.getSystemId());
		streamSource.setPublicId(inputSource.getPublicId());
		if (inputSource.getByteStream() != null) {
			if (inputSource.getEncoding() != null) {
				streamSource.setReader(new InputStreamReader(inputSource.getByteStream(), inputSource.getEncoding()));
			} else {
				streamSource.setInputStream(inputSource.getByteStream());
			}
		} else {
			streamSource.setReader(inputSource.getCharacterStream());
		}
		return streamSource;
	}

	protected abstract SAXSource createSAXSource(Context context, ESBMessage message, XQItem item) throws Exception;

	protected abstract FeatureFilter createXMLFilter(Context context, ESBMessage message, XMLReader parent) throws Exception;

	private SAXSource createSAXSource(Context context, ESBMessage message, Source source) throws Exception {
		XMLReader parent = null;
		if (source instanceof SAXSource) {
			SAXSource saxSource = (SAXSource) source;
			parent = saxSource.getXMLReader();
		}
		FeatureFilter xmlFilter = createXMLFilter(context, message, parent);
		InputSource inputSource = SAXSource.sourceToInputSource(source);
		if (inputSource == null) {
			throw new IllegalStateException("Message is invalid");			
		}
		return new SAXSource(xmlFilter, inputSource);
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		SAXSource source;
		switch (message.getBodyType()) {
		case XQ_SEQUENCE:
			XQSequence sequence = message.getBody();
			if (!sequence.next()) {
				throw new ExecutionException(this, "body not passed through");
			}
			source = createSAXSource(context, message, sequence.getItem());
			break;
		case XQ_ITEM:
			source = createSAXSource(context, message, message.<XQItem> getBody());
			break;
		default:
			source = createSAXSource(context, message, message.getBodyAsSource(context));
			break;
		}
		message.reset(BodyType.SOURCE, source);
		return new ExecutionContext(source);
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		if (nextActionIsPipelineStop) {
			if (message.isSink()) {
				context.getIdenticalTransformer().transform(execContext.<SAXSource> getResource(), message.getBodyAsSinkResult(context));
			} else {
				StringWriter sw = new StringWriter();
				context.getIdenticalTransformer().transform(execContext.<SAXSource> getResource(), new StreamResult(sw));
				message.reset(BodyType.STRING, sw.toString());
			}
		}
	}

}
