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

	public static StreamSource InputSourceToSource(InputSource inputSource) throws UnsupportedEncodingException {
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

	protected abstract SAXSource createSAXSource(XQItem item, Context context, ESBMessage message) throws Exception;

	protected abstract FeatureFilter createXMLFilter(XMLReader parent, Context context, ESBMessage message) throws Exception;

	private SAXSource createSAXSource(Source source, Context context, ESBMessage message) throws Exception {
		XMLReader parent = null;
		if (source instanceof SAXSource) {
			SAXSource saxSource = (SAXSource) source;
			parent = saxSource.getXMLReader();
		}
		FeatureFilter xmlFilter = createXMLFilter(parent, context, message);
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
			source = createSAXSource(sequence.getItem(), context, message);
			break;
		case XQ_ITEM:
			source = createSAXSource(message.<XQItem> getBody(), context, message);
			break;
		default:
			source = createSAXSource(message.getBodyAsSource(context), context, message);
			break;
		}
		message.reset(BodyType.SOURCE, source);
		return new ExecutionContext(source);
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		if (nextActionIsPipelineStop) {
			context.getIdenticalTransformer().transform(execContext.<SAXSource> getResource(), message.getBodyAsSinkResult(context));
		}
	}

}
