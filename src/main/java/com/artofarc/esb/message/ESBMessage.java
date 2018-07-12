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
package com.artofarc.esb.message;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

import javax.mail.BodyPart;
import javax.mail.MessagingException;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stax.StAXSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.xpath.XPathVariableResolver;
import javax.xml.xquery.XQException;
import javax.xml.xquery.XQItem;
import javax.xml.xquery.XQSequence;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.artofarc.esb.action.Action;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.http.HttpConstants;

public final class ESBMessage implements Cloneable, XPathVariableResolver {

	public final static String CHARSET_DEFAULT = "UTF-8";
	public final static int MTU = 4096;

	private final HashMap<String, Object> _headers = new HashMap<>();
	private final HashMap<String, Object> _variables = new HashMap<>();
	private final HashMap<String, BodyPart> _attachments = new HashMap<>();

	private BodyType _bodyType;
	private Object _body;
	private String _charsetName;
	private long _timeleft = 300000L;
	private boolean _join = true;
	private Action _terminal;

	public ESBMessage(BodyType bodyType, Object body) {
		reset(bodyType, body);
	}

	@Override
	public String toString() {
		return "ESBMessage [_bodyType=" + _bodyType + ", _charsetName=" + _charsetName + ", _headers=" + _headers + ", _variables=" + _variables + "]";
	}

	public void reset(BodyType bodyType, Object body) {
		if (bodyType == null) {
			// auto detect
			if (body instanceof String) {
				_bodyType = BodyType.STRING;
			} else if (body instanceof byte[]) {
				_bodyType = BodyType.BYTES;
			} else {
				throw new NullPointerException("bodyType is null and not auto detected");
			}
		} else {
			_bodyType = bodyType;
		}
		_body = body;
	}

	public Map<String, Object> getHeaders() {
		return _headers;
	}

	public Map<String, Object> getVariables() {
		return _variables;
	}

	public Map<String, BodyPart> getAttachments() {
		return _attachments;
	}

	@SuppressWarnings("unchecked")
	public <T extends Object> T getHeader(String headerName) {
		for (Entry<String, Object> entry : _headers.entrySet()) {
			if (headerName.equalsIgnoreCase(entry.getKey())) {
				return (T) entry.getValue();
			}
		}
		return null;
	}

	public <T extends Object> T putHeader(String headerName, Object value) {
		for (Entry<String, Object> entry : _headers.entrySet()) {
			if (headerName.equalsIgnoreCase(entry.getKey())) {
				@SuppressWarnings("unchecked")
				T oldValue = (T) entry.getValue();
				entry.setValue(value);
				return oldValue;
			}
		}
		_headers.put(headerName, value);
		return null;
	}

	@SuppressWarnings("unchecked")
	public <T extends Object> T removeHeader(String headerName) {
		for (Iterator<Entry<String, Object>> iter = _headers.entrySet().iterator(); iter.hasNext();) {
			Entry<String, Object> entry = iter.next();
			if (headerName.equalsIgnoreCase(entry.getKey())) {
				iter.remove();
				return (T) entry.getValue();
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	public <T extends Object> T getVariable(String varName) {
		return (T) _variables.get(varName);
	}

	public <T extends Object> T getVariable(String varName, T def) {
		@SuppressWarnings("unchecked")
		T result = (T) _variables.get(varName);
		return result != null ? result : def;
	}

	@SuppressWarnings("unchecked")
	public <T extends Object> T putVariable(String varName, Object value) {
		return (T) (value != null ? _variables.put(varName, value) : _variables.remove(varName));
	}
	
	public <T extends Object> T removeVariable(String varName) {
		@SuppressWarnings("unchecked")
		T result = (T) _variables.remove(varName);
		if (result == null) {
			throw new IllegalStateException("Variable not set: " + varName);
		}
		return result;
	}
	
	public void addAttachment(BodyPart bodyPart) throws MessagingException {
		String[] header = bodyPart.getHeader(HttpConstants.HTTP_HEADER_CONTENT_ID);
		if (header.length > 0) {
			_attachments.put(header[0], bodyPart);
		}
	}

	public String getCharsetName() {
		return _charsetName;
	}

	public void setCharsetName(String codePage) {
		_charsetName = codePage;
	}

	public long getTimeleft() {
		return _timeleft;
	}

	public void setTimeleft(long timeleft) {
		_timeleft = timeleft;
	}

	@SuppressWarnings("unchecked")
	public <T extends Object> T getBody() {
		return (T) _body;
	}

	public BodyType getBodyType() {
		return _bodyType;
	}

	public boolean isJoin() {
		return _join;
	}

	public void setJoin(boolean join) {
		_join = join;
	}

	public Action getTerminal() {
		return _terminal;
	}

	public void setTerminal(Action terminal) {
		_terminal = terminal;
	}
	
	private OutputStream getCompressedOutputStream(OutputStream outputStream) throws IOException {
		final String contentEncoding = getHeader(HttpConstants.HTTP_HEADER_CONTENT_ENCODING);
		if (contentEncoding != null) {
			switch (contentEncoding) {
			case "gzip":
				outputStream = new GZIPOutputStream(outputStream, MTU);
				break;
			case "deflate":
				outputStream = new DeflaterOutputStream(outputStream);
				break;
			}
		}
		return outputStream;
	}
	
	public InputStream getUncompressedInputStream() throws IOException {
		InputStream inputStream = (InputStream) _body;
		final String contentEncoding = removeHeader(HttpConstants.HTTP_HEADER_CONTENT_ENCODING);
		if (contentEncoding != null) {
			switch (contentEncoding) {
			case "gzip":
				inputStream = new GZIPInputStream(inputStream, MTU);
				break;
			case "deflate":
				inputStream = new InflaterInputStream(inputStream);
				break;
			default:
				throw new IOException("Unsupported content encoding " + contentEncoding);
			}
		}
		return inputStream;
	}

	private InputStreamReader getInputStreamReader() throws IOException {
		return new InputStreamReader(getUncompressedInputStream(), _charsetName != null ? _charsetName : CHARSET_DEFAULT);
	}

	public final static void copyStream(InputStream is, OutputStream os) throws IOException {
		final byte[] buffer = new byte[MTU];
		int len;
		while ((len = is.read(buffer)) >= 0) {
			os.write(buffer, 0, len);
		}
		// is.close();
	}

	public byte[] getBodyAsByteArray(Context context) throws TransformerException, IOException, XQException {
		byte[] ba = null;
		ByteArrayOutputStream bos;
		switch (_bodyType) {
		case DOM:
			bos = new ByteArrayOutputStream(MTU);
			writeTo(bos, context);
			bos.close();
			ba = bos.toByteArray();
			break;
		case STRING:
			if (_charsetName == null) {
				_charsetName = CHARSET_DEFAULT;
			}
			ba = ((String) _body).getBytes(_charsetName);
			break;
		case BYTES:
			return (byte[]) _body;
		case INPUT_STREAM:
			bos = new ByteArrayOutputStream(MTU);
			copyStream(getUncompressedInputStream(), bos);
			bos.close();
			ba = bos.toByteArray();
			break;
		case XQ_ITEM:
			bos = new ByteArrayOutputStream(MTU);
			XQItem xqItem = (XQItem) _body;
			xqItem.writeItem(bos, null);
			bos.close();
			ba = bos.toByteArray();
			break;
		case INVALID:
			throw new IllegalStateException("Message is invalid");
		default:
			throw new IllegalStateException("BodyType not allowed: " + _bodyType);
		}
		_body = ba;
		_bodyType = BodyType.BYTES;
		return ba;
	}

	public String getBodyAsString(Context context) throws TransformerException, IOException, XQException {
		String str = null;
		switch (_bodyType) {
		case DOM:
			StringWriter sw = new StringWriter();
			transform(context.getIdenticalTransformer(), new StreamResult(sw));
			sw.close();
			str = sw.toString();
			break;
		case STRING:
			return (String) _body;
		case BYTES:
			str = new String((byte[]) _body, _charsetName != null ? _charsetName : CHARSET_DEFAULT);
			break;
		case INPUT_STREAM:
		case XQ_ITEM:
			getBodyAsByteArray(context);
			return getBodyAsString(context);
		case INVALID:
			throw new IllegalStateException("Message is invalid");
		default:
			throw new IllegalStateException("BodyType not allowed: " + _bodyType);
		}
		_body = str;
		_bodyType = BodyType.STRING;
		return str;
	}

	public Document getBodyAsDOM(Context context) throws SAXException, IOException {
		InputSource inputSource = null;
		switch (_bodyType) {
		case DOM:
			return (Document) _body;
		case STRING:
			inputSource = new InputSource(new StringReader((String) _body));
			break;
		case BYTES:
			_body = new ByteArrayInputStream((byte[]) _body);
			// nobreak
		case INPUT_STREAM:
			inputSource = new InputSource(getInputStreamReader());
			break;
		case INVALID:
			throw new IllegalStateException("Message is invalid");
		default:
			throw new IllegalStateException("BodyType not allowed: " + _bodyType);
		}
		_body = context.getDocumentBuilder().parse(inputSource);
		_bodyType = BodyType.DOM;
		return (Document) _body;
	}

	public InputStream getBodyAsInputStream(Context context) throws TransformerException, IOException, XQException {
		switch (_bodyType) {
		case INPUT_STREAM:
			return getUncompressedInputStream();
		default:
			return new ByteArrayInputStream(getBodyAsByteArray(context));
		}
	}
	
	public Reader getBodyAsReader(Context context) throws TransformerException, IOException, XQException {
		switch (_bodyType) {
		case READER:
			return (Reader) _body;
		case INPUT_STREAM:
			return getInputStreamReader();
		default:
			return new StringReader(getBodyAsString(context));
		}
	}
	
	public Source getBodyAsSource() throws IOException {
		switch (_bodyType) {
		case DOM:
			return new DOMSource((Document) _body);
		case EXCEPTION:
			reset(BodyType.STRING, asXMLString((Exception) _body));
		case STRING:
			return new StreamSource(new StringReader((String) _body));
		case BYTES:
			_body = new ByteArrayInputStream((byte[]) _body);
			// nobreak
		case INPUT_STREAM:
			_bodyType = BodyType.INVALID;
			return new StreamSource(getInputStreamReader());
		case READER:
			_bodyType = BodyType.INVALID;
			return new StreamSource((Reader) _body);
		default:
			throw new IllegalStateException("Message is invalid");
		}
	}

	private static String asXMLString(Exception e) {
		String xml = "<exception><message>" + e.getMessage() + "</message>";
		if (e.getCause() != null) {
			xml += "<cause>" + e.getCause().getMessage() + "</cause>";
		}
		xml += "</exception>";
		return xml;
	}

	public XMLStreamReader getBodyAsXMLStreamReader(Context context) throws XQException, XMLStreamException, IOException {
		switch (_bodyType) {
		case XQ_SEQUENCE:
			XQSequence xqSequence = (XQSequence) _body;
			if (!xqSequence.next()) {
				throw new IllegalStateException("Message already consumed");
			}
			_body = context.getXQDataFactory().createItem(xqSequence.getItem());
		case XQ_ITEM:
			_bodyType = BodyType.INVALID;
			return ((XQItem) _body).getItemAsStream();
		default:
			return context.getPoolContext().getGlobalContext().getXMLInputFactory().createXMLStreamReader(getBodyAsSource());
		}
	}

	public InputSource getBodyAsSaxSource() throws IOException {
		switch (_bodyType) {
		case SOURCE:
			return (InputSource) _body;
		case STRING:
			return new InputSource(new StringReader((String) _body));
		case BYTES:
			return new InputSource(new ByteArrayInputStream((byte[]) _body));
		case INPUT_STREAM:
			_bodyType = BodyType.INVALID;
			return new InputSource(getInputStreamReader());
		default:
			throw new IllegalStateException("Message is invalid");
		}
	}

	public boolean isSink() {
		return _bodyType == BodyType.OUTPUT_STREAM || _bodyType == BodyType.WRITER;
	}

	public boolean isStream() {
		return _bodyType == BodyType.INPUT_STREAM || _bodyType == BodyType.READER;
	}

	public Result getBodyAsSinkResult() throws IOException {
		switch (_bodyType) {
		case OUTPUT_STREAM:
			_body = getCompressedOutputStream((OutputStream) _body);
			return new StreamResult((OutputStream) _body);
		case WRITER:
			return new StreamResult((Writer) _body);
		default:
			throw new IllegalStateException("Message cannot be converted to Result: " + _bodyType);
		}
	}

	private void transform(Transformer transformer, Result result) throws TransformerException, XQException, IOException {
		Source source = null;
		switch (_bodyType) {
		case DOM:
			source = new DOMSource((Document) _body);
			break;
		case STRING:
			source = new StreamSource(new StringReader((String) _body));
			break;
		case BYTES:
			source = new StreamSource(new ByteArrayInputStream((byte[]) _body));
			break;
		case INPUT_STREAM:
			_bodyType = BodyType.INVALID;
			source = new StreamSource(getInputStreamReader());
			break;
		case READER:
			source = new StreamSource((Reader) _body);
			break;
		case XQ_ITEM:
			XQItem xqItem = (XQItem) _body;
			source = new StAXSource(xqItem.getItemAsStream());
			break;
		case INVALID:
			throw new IllegalStateException("Message is invalid");
		default:
			throw new IllegalStateException("BodyType not allowed: " + _bodyType);
		}
		transformer.transform(source, result);
	}

	public void writeTo(Result result, Context context) throws TransformerException, XQException, IOException {
		if (_bodyType == BodyType.XQ_ITEM) {
			XQItem xqItem = (XQItem) _body;
			xqItem.writeItemToResult(result);
		} else {
			transform(context.getIdenticalTransformer(), result);
		}
	}

	public void writeToCompressedOutputStream(OutputStream os, Context context) throws TransformerException, IOException, XQException {
		OutputStream outputStream = getCompressedOutputStream(os);
		writeTo(outputStream, context);
		outputStream.close();
	}
	
	public void writeTo(OutputStream os, Context context) throws TransformerException, IOException, XQException {
		switch (_bodyType) {
		case DOM:
			transform(context.getIdenticalTransformer(), _charsetName != null ? new StreamResult(new OutputStreamWriter(os, _charsetName)) : new StreamResult(os));
			break;
		case STRING:
			os.write(getBodyAsByteArray(context));
			break;
		case BYTES:
			os.write((byte[]) _body);
			break;
		case INPUT_STREAM:
			// writes compressed data through!
			try {
				copyStream((InputStream) _body, os);
			} finally {
				_body = null;
				_bodyType = BodyType.INVALID;
			}
			break;
		case XQ_ITEM:
			XQItem xqItem = (XQItem) _body;
			xqItem.writeItem(os, null);
			break;
		case XQ_SEQUENCE:
			// dummy
			XQSequence xqSequence = (XQSequence) _body;
			if (xqSequence.next()) {
				xqSequence.writeItem(os, null);
			} else {
				throw new IllegalStateException("Message already consumed");
			}
			break;
		case INVALID:
			throw new IllegalStateException("Message is invalid");
		default:
			throw new IllegalStateException("BodyType not allowed: " + _bodyType);
		}
	}

	@Override
	public ESBMessage clone() throws CloneNotSupportedException {
		return (ESBMessage) super.clone();
	}

	public ESBMessage copy(Context context, boolean withBody) throws Exception {
		final ESBMessage clone;
		if (withBody) {
			final Object newBody;
			switch (_bodyType) {
			case INPUT_STREAM:
				newBody = getBodyAsByteArray(context);
				break;
			case XQ_ITEM:
				// TOREVIEW: Should be the context of the Thread receiving this copy
				newBody = context.getXQDataFactory().createItem((XQItem) _body);
				break;
			default:
				newBody = _body;
				break;
			}
			clone = new ESBMessage(_bodyType, newBody);
		} else {
			clone = new ESBMessage(BodyType.INVALID, null);
		}
		clone.setCharsetName(_charsetName);
		clone.getHeaders().putAll(_headers);
		clone.getVariables().putAll(_variables);
		return clone;
	}

	@Override
	public Object resolveVariable(QName variableName) {
		// if using namespaces, there's more to do here
		String key = variableName.getLocalPart();
		return _variables.get(key);
	}

}
