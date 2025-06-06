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

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.function.BiConsumer;

import javax.xml.parsers.SAXParser;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.xquery.XQException;
import javax.xml.xquery.XQItem;
import javax.xml.xquery.XQSequence;

import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.RichSource;
import com.artofarc.util.ReflectionUtils;
import com.artofarc.util.XMLFilterBase;

public abstract class SAXAction extends Action {

	protected static class XQJFilter extends XMLFilterBase {
		protected final XQItem _item;

		public XQJFilter(XQItem item) {
			_item = item;
		}

		@Override
		public void parse(InputSource source) throws SAXException {
			try {
				_item.writeItemToResult(new SAXResult(getContentHandler()));
			} catch (XQException e) {
				throw ReflectionUtils.convert(e, SAXException.class);
			}
		}
	}

	protected static class ReuseParserXMLFilter extends XMLFilterBase {
		private final SAXParser _saxParser;

		public ReuseParserXMLFilter(SAXParser saxParser) throws SAXException {
			super(saxParser.getXMLReader());
			_saxParser = saxParser;
		}

		@Override
		public void parse(InputSource input) throws SAXException, IOException {
			try {
				super.parse(input);
			} finally {
				_saxParser.reset();
			}
		}
	}

	protected static class ChainingXMLFilter<T extends ContentHandler> extends XMLFilterBase {
		private final SAXParser _saxParser;
		private final T _handler;
		private final BiConsumer<T, ContentHandler> _setter;

		public ChainingXMLFilter(SAXParser saxParser, T handler, BiConsumer<T, ContentHandler> setter) throws SAXException {
			super(saxParser.getXMLReader());
			_saxParser = saxParser;
			super.setContentHandler(_handler = handler);
			_setter = setter;
		}

		public ChainingXMLFilter(XMLReader parent, T handler, BiConsumer<T, ContentHandler> setter) {
			super(parent);
			_saxParser = null;
			parent.setContentHandler(_handler = handler);
			_setter = setter;
		}

		@Override
		public void setContentHandler(ContentHandler handler) {
			_setter.accept(_handler, handler);
		}

		@Override
		public void parse(InputSource source) throws SAXException, IOException {
			if (_saxParser != null) {
				try {
					super.parse(source);
				} finally {
					_saxParser.reset();
				}
			} else {
				getParent().parse(source);
			}
		}
	}

	protected static class DOMFilter extends XMLFilterBase {
		private final Context _context;
		private final DOMSource _domSource;

		public DOMFilter(Context context, DOMSource domSource) {
			_context = context;
			_domSource = domSource;
		}

		@Override
		public void parse(InputSource source) throws SAXException {
			try {
				_context.transformRaw(_domSource, new SAXResult(getContentHandler()));
			} catch (TransformerException e) {
				throw ReflectionUtils.convert(e, SAXException.class);
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

	public SAXAction() {
		_streamingToSink = true;
	}

	@Deprecated
	protected SAXSource createSAXSource(Context context, ESBMessage message, XQItem item) throws Exception {
		throw new UnsupportedOperationException();
	}

	protected RichSource createSource(Context context, ESBMessage message, XQItem item) throws Exception {
		return new RichSource(createSAXSource(context, message, item), item, null);
	}

	protected abstract XMLFilterBase createXMLFilter(Context context, ESBMessage message, XMLReader parent) throws Exception;

	private RichSource createSource(Context context, ESBMessage message, Source source) throws Exception {
		XMLReader parent = null;
		boolean xqItemKindElement = message.getBodyType() == BodyType.SOURCE ? message.<RichSource> getBody().isXQItemKindElement() : false;
		if (source instanceof SAXSource) {
			SAXSource saxSource = (SAXSource) source;
			parent = saxSource.getXMLReader();
		} else if (source instanceof DOMSource) {
			DOMSource domSource = (DOMSource) source;
			parent = new DOMFilter(context, domSource);
			xqItemKindElement = true;
		}
		InputSource inputSource = SAXSource.sourceToInputSource(source);
		return new RichSource(new SAXSource(createXMLFilter(context, message, parent), inputSource), xqItemKindElement);
	}

	@Override
	protected ExecutionContext prepare(Context context, ESBMessage message, boolean inPipeline) throws Exception {
		RichSource source;
		switch (message.getBodyType()) {
		case XQ_SEQUENCE:
			XQSequence sequence = message.getBody();
			if (!sequence.next()) {
				throw new ExecutionException(this, "body not passed through");
			}
			message.reset(BodyType.XQ_ITEM, context.getXQDataFactory().createItem(sequence.getItem()));
			// nobreak
		case XQ_ITEM:
			source = createSource(context, message, message.<XQItem> getBody());
			break;
		default:
			source = createSource(context, message, message.getBodyAsSource(context));
			break;
		}
		message.reset(BodyType.SOURCE, source);
		message.removeHeader(HttpConstants.HTTP_HEADER_CONTENT_LENGTH);
		return new ExecutionContext(source);
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		if (nextActionIsPipelineStop) {
			RichSource source = execContext.getResource();
			if (message.isSink()) {
				context.transform(source.getSource(), message.createResultFromBodyAsSink(context), message.getVariable(ESBConstants.serializationParameters));
			} else {
				message.reset(BodyType.SOURCE, source);
			}
		}
	}

}
