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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

import javax.mail.MessagingException;
import javax.mail.internet.MimeBodyPart;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stax.StAXSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.xquery.XQException;
import javax.xml.xquery.XQItem;
import javax.xml.xquery.XQSequence;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import com.artofarc.esb.context.Context;
import static com.artofarc.esb.http.HttpConstants.*;
import com.artofarc.esb.resource.SchemaAwareFISerializerFactory;
import com.artofarc.util.FastInfosetDeserializer;
import com.artofarc.util.SchemaAwareFastInfosetSerializer;

public final class ESBMessage implements Cloneable {

	public static final Charset CHARSET_DEFAULT = StandardCharsets.UTF_8;
	public static final int MTU = Integer.parseInt(System.getProperty("esb0.internalMTU", "4096"));
	private static final String XML_OUTPUT_INDENT = System.getProperty("esb0.xmlOutputIndent", "yes");

	private final HashMap<String, Object> _headers = new HashMap<>();
	private final HashMap<String, Object> _variables = new HashMap<>();
	private final HashMap<String, MimeBodyPart> _attachments = new HashMap<>();

	private BodyType _bodyType;
	private Object _body;
	private Charset _charset, _sinkEncoding; 
	private Schema _schema;

	public ESBMessage(BodyType bodyType, Object body) {
		_variables.put(ESBConstants.initialTimestamp, System.currentTimeMillis());
		reset(bodyType, body);
	}

	@Override
	public String toString() {
		return "ESBMessage [_bodyType=" + _bodyType + ", _charsetName=" + _charset + ", _headers=" + _headers + ", _variables=" + _variables + "]";
	}

	@SuppressWarnings("unchecked")
	public <T> T reset(BodyType bodyType, Object body) {
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
		switch (_bodyType) {
		case INVALID:
		case STRING:
		case XQ_ITEM:
		case DOM:
		case SOURCE:
		case READER:
			_charset = null;
			break;
		default:
			break;
		}
		return (T) (_body = body);
	}

	public Map<String, Object> getHeaders() {
		return _headers;
	}

	public Map<String, Object> getVariables() {
		return _variables;
	}

	public Map<String, MimeBodyPart> getAttachments() {
		return _attachments;
	}

	@SuppressWarnings("unchecked")
	public <T> T getHeader(String headerName) {
		for (Entry<String, Object> entry : _headers.entrySet()) {
			if (headerName.equalsIgnoreCase(entry.getKey())) {
				return (T) entry.getValue();
			}
		}
		return null;
	}

	public <T> T putHeader(String headerName, Object value) {
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
	public <T> T removeHeader(String headerName) {
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
	public <T> T getVariable(String varName) {
		return (T) _variables.get(varName);
	}

	public <T> T getVariable(String varName, T def) {
		@SuppressWarnings("unchecked")
		T result = (T) _variables.get(varName);
		return result != null ? result : def;
	}

	@SuppressWarnings("unchecked")
	public <T> T putVariable(String varName, Object value) {
		return (T) (value != null ? _variables.put(varName, value) : _variables.remove(varName));
	}
	
	public <T> T removeVariable(String varName) {
		@SuppressWarnings("unchecked")
		T result = (T) _variables.remove(varName);
		if (result == null) {
			throw new IllegalStateException("Variable not set: " + varName);
		}
		return result;
	}
	
	public void addAttachment(MimeBodyPart bodyPart) throws MessagingException {
		String cid = bodyPart.getContentID();
		cid = cid.substring(1, cid.length() - 1);
		_attachments.put(cid, bodyPart);
	}

	public Charset getCharset() {
		return _charset != null ? _charset : CHARSET_DEFAULT;
	}

	public void setCharset(String charsetName) {
		_charset = charsetName != null ? Charset.forName(charsetName) : null;
	}

	public void setSinkEncoding(String sinkEncoding) {
		_sinkEncoding = Charset.forName(sinkEncoding);
	}

	public String getSinkEncoding() {
		return (_sinkEncoding != null ? _sinkEncoding : getCharset()).name();
	}
	
	private Properties getSinkProperties() {
		Properties props = new Properties();
		props.setProperty(OutputKeys.ENCODING, getSinkEncoding());
		props.setProperty(OutputKeys.INDENT, XML_OUTPUT_INDENT);
		return props;
	}

	private boolean isSinkEncodingdifferent() {
		return _sinkEncoding != null && _sinkEncoding != getCharset();
	}
	
	public Number getTimeleft(Number def) {
		return getVariable(ESBConstants.timeleft, def);
	}

	@SuppressWarnings("unchecked")
	public <T> T getBody() {
		return (T) _body;
	}

	public BodyType getBodyType() {
		return _bodyType;
	}

	public Schema getSchema() {
		return _schema;
	}

	public void setSchema(Schema schema) {
		_schema = schema;
	}

	private OutputStream getCompressedOutputStream(OutputStream outputStream) throws IOException {
		final String contentEncoding = getHeader(HTTP_HEADER_CONTENT_ENCODING);
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
		final String contentEncoding = removeHeader(HTTP_HEADER_CONTENT_ENCODING);
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
		return new InputStreamReader(getUncompressedInputStream(), getCharset());
	}

	public static void copyStream(InputStream is, OutputStream os) throws IOException {
		final byte[] buffer = new byte[MTU];
		int len;
		while ((len = is.read(buffer)) >= 0) {
			os.write(buffer, 0, len);
		}
	}

	public static void copyStream(Reader is, Writer os) throws IOException {
		final char[] buffer = new char[MTU];
		int len;
		while ((len = is.read(buffer)) >= 0) {
			os.write(buffer, 0, len);
		}
	}

	public byte[] getBodyAsByteArray(Context context) throws TransformerException, IOException, XQException {
		byte[] ba;
		ByteArrayOutputStream bos;
		switch (_bodyType) {
		case DOM:
			bos = new ByteArrayOutputStream(MTU);
			writeRawTo(bos, context);
			ba = bos.toByteArray();
			_charset = _sinkEncoding;
			break;
		case STRING:
			ba = ((String) _body).getBytes(getSinkEncoding());
			_charset = _sinkEncoding;
			break;
		case BYTES:
			return (byte[]) _body;
		case INPUT_STREAM:
			bos = new ByteArrayOutputStream(MTU);
			copyStream(getUncompressedInputStream(), bos);
			ba = bos.toByteArray();
			break;
		case XQ_ITEM:
			bos = new ByteArrayOutputStream(MTU);
			XQItem xqItem = (XQItem) _body;
			xqItem.writeItem(bos, getSinkProperties());
			ba = bos.toByteArray();
			_charset = _sinkEncoding;
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
		String str;
		StringWriter sw;
		switch (_bodyType) {
		case DOM:
			sw = new StringWriter();
			transform(context.getIdenticalTransformer(), new StreamResult(sw));
			str = sw.toString();
			break;
		case STRING:
			return (String) _body;
		case BYTES:
			str = new String((byte[]) _body, getCharset());
			break;
		case INPUT_STREAM:
		case XQ_ITEM:
			getBodyAsByteArray(context);
			return getBodyAsString(context);
		case READER:
			sw = new StringWriter();
			copyStream((Reader) _body, sw);
			str = sw.toString();
			break;
		case EXCEPTION:
			str = asXMLString((Exception) _body);
			break;
		case INVALID:
			throw new IllegalStateException("Message is invalid");
		default:
			throw new IllegalStateException("BodyType not allowed: " + _bodyType);
		}
		return reset(BodyType.STRING, str);
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
			return reset(BodyType.READER, getInputStreamReader());
		default:
			return new StringReader(getBodyAsString(context));
		}
	}
	
	public Source getBodyAsSource() throws IOException {
		final String contentType = getHeader(HTTP_HEADER_CONTENT_TYPE);
		if (contentType != null && (contentType.startsWith(HTTP_HEADER_CONTENT_TYPE_FI_SOAP11) || contentType.startsWith(HTTP_HEADER_CONTENT_TYPE_FI_SOAP12))) {
			// TODO Cache FastInfosetDeserializer
			return new SAXSource(new FastInfosetDeserializer().getFastInfosetReader(), getBodyAsSaxSource());
		}
		return getBodyAsSourceInternal();
	}
	
	private Source getBodyAsSourceInternal() throws IOException {
		switch (_bodyType) {
		case SOURCE:
			return (Source) _body;
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
			return reset(BodyType.INVALID, new StreamSource(getInputStreamReader()));
		case READER:
			_bodyType = BodyType.INVALID;
			return new StreamSource((Reader) _body);
		default:
			throw new IllegalStateException("Message is invalid");
		}
	}

	private static String asXMLString(Exception e) {
		String xml = "<exception><message><![CDATA[" + e.getMessage() + "]]></message>";
		if (e.getCause() != null) {
			xml += "<cause><![CDATA[" + e.getCause().getMessage() + "]]></cause>";
		}
		xml += "</exception>";
		return xml;
	}

	public XMLStreamReader getBodyAsXMLStreamReader(Context context) throws IOException, XQException, XMLStreamException {
		switch (_bodyType) {
		case XQ_SEQUENCE:
			XQSequence xqSequence = (XQSequence) _body;
			if (!xqSequence.next()) {
				throw new IllegalStateException("Message already consumed");
			}
			_body = context.getXQDataFactory().createItem(xqSequence.getItem());
			// nobreak
		case XQ_ITEM:
			_bodyType = BodyType.INVALID;
			return ((XQItem) _body).getItemAsStream();
		default:
			return context.getPoolContext().getGlobalContext().getXMLInputFactory().createXMLStreamReader(getBodyAsSource());
		}
	}

	private InputSource getBodyAsSaxSource() throws IOException {
		switch (_bodyType) {
		case BYTES:
			reset(BodyType.STRING, new String((byte[]) _body, getCharset()));
			// nobreak
		case STRING:
			return new InputSource(new StringReader((String) _body));
		case INPUT_STREAM:
			return reset(BodyType.INVALID, new InputSource(getInputStreamReader()));
		default:
			throw new IllegalStateException("Message is invalid");
		}
	}

	public boolean isSink() {
		return _bodyType == BodyType.RESULT || _bodyType == BodyType.OUTPUT_STREAM || _bodyType == BodyType.WRITER;
	}

	public boolean isStream() {
		return _bodyType == BodyType.INPUT_STREAM || _bodyType == BodyType.READER;
	}

	public Result getBodyAsSinkResult(Context context) throws Exception {
		final String contentType = getHeader(HTTP_HEADER_CONTENT_TYPE);
		if (HTTP_HEADER_CONTENT_TYPE_FI_SOAP11.equals(contentType) || HTTP_HEADER_CONTENT_TYPE_FI_SOAP12.equals(contentType)) {
			if (_bodyType == BodyType.OUTPUT_STREAM) {
				SchemaAwareFastInfosetSerializer serializer = context.getResourceFactory(SchemaAwareFISerializerFactory.class).getResource(_schema);
				serializer.getFastInfosetSerializer().setOutputStream(getCompressedOutputStream((OutputStream) _body));
				serializer.getFastInfosetSerializer().setCharacterEncodingScheme(getSinkEncoding());
				return new SAXResult(serializer.getContentHandler());
			} else {
				throw new IllegalStateException("Message cannot be converted to FastInfoset: " + _bodyType);
			}
		}
		switch (_bodyType) {
		case RESULT:
			return (Result) _body;
		case OUTPUT_STREAM:
			_body = getCompressedOutputStream((OutputStream) _body);
			if (isSinkEncodingdifferent()) {
				_body = new OutputStreamWriter((OutputStream) _body, _sinkEncoding);
				// nobreak
			} else {
				return new StreamResult((OutputStream) _body);
			}
		case WRITER:
			return new StreamResult((Writer) _body);
		default:
			throw new IllegalStateException("Message cannot be converted to Result: " + _bodyType);
		}
	}

	private void transform(Transformer transformer, Result result) throws TransformerException, XQException, IOException {
		Source source;
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

	public void writeTo(OutputStream os, Context context) throws Exception {
		OutputStream outputStream = getCompressedOutputStream(os);
		final String contentType = getHeader(HTTP_HEADER_CONTENT_TYPE);
		if (HTTP_HEADER_CONTENT_TYPE_FI_SOAP11.equals(contentType) || HTTP_HEADER_CONTENT_TYPE_FI_SOAP12.equals(contentType)) {
			SchemaAwareFastInfosetSerializer serializer = context.getResourceFactory(SchemaAwareFISerializerFactory.class).getResource(_schema);
			serializer.getFastInfosetSerializer().setOutputStream(outputStream);
			serializer.getFastInfosetSerializer().setCharacterEncodingScheme(getSinkEncoding());
			writeTo(new SAXResult(serializer.getContentHandler()), context);
		} else {
			writeRawTo(outputStream, context);
		}
		outputStream.close();
	}
	
	public void writeRawTo(OutputStream os, Context context) throws TransformerException, IOException, XQException {
		switch (_bodyType) {
		case DOM:
			transform(context.getIdenticalTransformer(), new StreamResult(new OutputStreamWriter(os, getSinkEncoding())));
			break;
		case STRING:
			os.write(getBodyAsByteArray(context));
			break;
		case BYTES:
			if (isSinkEncodingdifferent()) {
				_body = getBodyAsString(context).getBytes(_sinkEncoding);
			}
			os.write((byte[]) _body);
			break;
		case INPUT_STREAM:
			try {
				if (isSinkEncodingdifferent()) {
					copyStream(getInputStreamReader(), new OutputStreamWriter(os, _sinkEncoding));
				} else {
					// writes compressed data through!
					copyStream((InputStream) _body, os);
				}
			} finally {
				_body = null;
				_bodyType = BodyType.INVALID;
			}
			break;
		case XQ_ITEM:
			XQItem xqItem = (XQItem) _body;
			xqItem.writeItem(os, getSinkProperties());
			break;
		case XQ_SEQUENCE:
			XQSequence xqSequence = (XQSequence) _body;
			if (xqSequence.next()) {
				xqSequence.writeItem(os, getSinkProperties());
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
			case READER:
				newBody = getBodyAsString(context);
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
		clone._charset = _charset;
		clone.getHeaders().putAll(_headers);
		clone.getVariables().putAll(_variables);
		return clone;
	}

}
