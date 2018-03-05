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
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;

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
import com.artofarc.util.CaseInsensitiveMap;

public final class ESBMessage implements Cloneable, XPathVariableResolver {

	public final static int MTU = 4096;

	private final HashMap<String, Object> _headers = new CaseInsensitiveMap<>();
	private final HashMap<String, Object> _variables = new HashMap<>();

	private BodyType _bodyType;
	private Object _body;
	private String _charsetName;
	private long _timeleft = 300000L;
	private boolean _join = true;
	private Action _terminal;

	public ESBMessage(BodyType bodyType, Object body) {
		_bodyType = bodyType;
		_body = body;
	}

	@Override
	public String toString() {
		return "ESBMessage [_bodyType=" + _bodyType + ", _charsetName=" + _charsetName + ", _headers=" + _headers + ", _variables=" + _variables + "]";
	}

	public void reset(BodyType bodyType, Object body) {
		_bodyType = bodyType;
		_body = body;
		// _headers.clear();
		// _charsetName = null;
	}

	public Map<String, Object> getHeaders() {
		return _headers;
	}

	public Map<String, Object> getVariables() {
		return _variables;
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

	public <T extends Object> T removeVariable(String varName) {
		@SuppressWarnings("unchecked")
		T result = (T) _variables.remove(varName);
		if (result == null) {
			throw new IllegalStateException("Variable not set: " + varName);
		}
		return result;
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

	private InputStreamReader getInputStreamReader() {
		try {
			return new InputStreamReader((InputStream) _body, _charsetName);
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
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
			bos = new ByteArrayOutputStream();
			writeTo(bos, context);
			bos.close();
			ba = bos.toByteArray();
			break;
		case STRING:
			ba = _charsetName != null ? ((String) _body).getBytes(_charsetName) : ((String) _body).getBytes();
			break;
		case BYTES:
			return (byte[]) _body;
		case INPUT_STREAM:
			bos = new ByteArrayOutputStream();
			copyStream((InputStream) _body, bos);
			bos.close();
			ba = bos.toByteArray();
			break;
		case XQ_ITEM:
			bos = new ByteArrayOutputStream();
			XQItem xqItem = (XQItem) _body;
			xqItem.writeItem(bos, null);
			bos.close();
			ba = bos.toByteArray();
			break;
		case INVALID:
			throw new IllegalStateException("Message already consumed");
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
			str = _charsetName != null ? new String((byte[]) _body, _charsetName) : new String((byte[]) _body);
			break;
		case INPUT_STREAM:
			getBodyAsByteArray(context);
			return getBodyAsString(context);
		case INVALID:
			throw new IllegalStateException("Message already consumed");
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
			inputSource = _charsetName != null ? new InputSource(getInputStreamReader()) : new InputSource((InputStream) _body);
			break;
		case INVALID:
			throw new IllegalStateException("Message already consumed");
		default:
			throw new IllegalStateException("BodyType not allowed: " + _bodyType);
		}
		_body = context.getDocumentBuilder().parse(inputSource);
		_bodyType = BodyType.DOM;
		return (Document) _body;
	}

	public Source getBodyAsSource() {
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
			return _charsetName != null ? new StreamSource(getInputStreamReader()) : new StreamSource((InputStream) _body);
		case READER:
			_bodyType = BodyType.INVALID;
			return new StreamSource((Reader) _body);
		default:
			throw new IllegalStateException("Message already consumed");
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

	public XMLStreamReader getBodyAsXMLStreamReader(Context context) throws XQException, XMLStreamException {
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

	public InputSource getBodyAsSaxSource() {
		switch (_bodyType) {
		case SOURCE:
			return (InputSource) _body;
		case STRING:
			return new InputSource(new StringReader((String) _body));
		case BYTES:
			return new InputSource(new ByteArrayInputStream((byte[]) _body));
		case INPUT_STREAM:
			_bodyType = BodyType.INVALID;
			return _charsetName != null ? new InputSource(getInputStreamReader()) : new InputSource((InputStream) _body);
		default:
			throw new IllegalStateException("Message already consumed");
		}
	}

	public boolean isSink() {
		return _bodyType == BodyType.OUTPUT_STREAM || _bodyType == BodyType.WRITER;
	}

	public Result getBodyAsSinkResult() {
		switch (_bodyType) {
		case OUTPUT_STREAM:
			return new StreamResult((OutputStream) _body);
		case WRITER:
			return new StreamResult((Writer) _body);
		default:
			throw new IllegalStateException("Message cannot be converted to Result: " + _bodyType);
		}
	}

	private void transform(Transformer transformer, Result result) throws TransformerException, XQException {
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
			source = _charsetName != null ? new StreamSource(getInputStreamReader()) : new StreamSource((InputStream) _body);
			break;
		case READER:
			source = new StreamSource((Reader) _body);
			break;
		case XQ_ITEM:
			XQItem xqItem = (XQItem) _body;
			source = new StAXSource(xqItem.getItemAsStream());
			break;
		case INVALID:
			throw new IllegalStateException("Message already consumed");
		default:
			throw new IllegalStateException("BodyType not allowed: " + _bodyType);
		}
		transformer.transform(source, result);
	}

	public void writeTo(Result result, Context context) throws TransformerException, XQException {
		if (_bodyType == BodyType.XQ_ITEM) {
			XQItem xqItem = (XQItem) _body;
			xqItem.writeItemToResult(result);
		} else {
			transform(context.getIdenticalTransformer(), result);
		}
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
			throw new IllegalStateException("Message already consumed");
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
