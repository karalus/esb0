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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.HashMap;
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
import com.artofarc.util.ByteArrayOutputStream;
import com.artofarc.util.SchemaAwareFastInfosetSerializer;
import com.artofarc.util.StreamUtils;
import com.artofarc.util.StringWriter;

public final class ESBMessage implements Cloneable {

	public static final Charset CHARSET_DEFAULT = java.nio.charset.StandardCharsets.UTF_8;
	public static final int MTU = StreamUtils.MTU;
	private static final String XML_OUTPUT_INDENT = System.getProperty("esb0.xmlOutputIndent", "yes");

	private final HashMap<String, Object> _headers = new HashMap<>();
	private final HashMap<String, Object> _variables = new HashMap<>();
	private final HashMap<String, MimeBodyPart> _attachments = new HashMap<>();

	private BodyType _bodyType;
	private Object _body;
	private Charset _charset, _sinkEncoding; 
	private Schema _schema;

	private ESBMessage(BodyType bodyType, Object body, Charset charset) {
		_variables.put(ESBConstants.initialTimestamp, System.currentTimeMillis());
		init(bodyType, body, charset);
	}

	public ESBMessage(BodyType bodyType, Object body) {
		this(bodyType, body, null);
	}

	@Override
	public String toString() {
		return "ESBMessage [_bodyType=" + _bodyType + ", _charsetName=" + _charset + ", _headers=" + _headers + ", _variables=" + _variables + "]";
	}

	@SuppressWarnings("unchecked")
	private <T> T init(BodyType bodyType, T body, Charset charset) {
		_bodyType = bodyType != null ? bodyType : BodyType.detect(body);
		_charset = charset;
		return (T) (_body = body);
	}

	public void reset(BodyType bodyType, Object body, String charsetName) {
		_bodyType = bodyType; 
		_body = body;
		setCharset(charsetName);
	}

	public void reset(BodyType bodyType, Object body) {
		if (bodyType == null) {
			bodyType = BodyType.detect(body);
		}
		if (bodyType.hasCharset()) {
			if (_bodyType.hasCharset() && _charset != null) {
				System.err.println("Warning: old binary type had non default charset");
			}
			init(bodyType, body, _charset); 
		} else {
			init(bodyType, body, null); 
		}
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
	public <T> Entry<String, T> getHeaderEntry(String headerName) {
		for (Entry<String, Object> entry : _headers.entrySet()) {
			if (headerName.equalsIgnoreCase(entry.getKey())) {
				return (Entry<String, T>) entry;
			}
		}
		return null;
	}

	public <T> T getHeader(String headerName) {
		Entry<String, T> entry = getHeaderEntry(headerName);
		return entry != null ? entry.getValue() : null;
	}

	public <T> T putHeader(String headerName, T value) {
		Entry<String, T> entry = getHeaderEntry(headerName);
		if (entry != null) {
			return entry.setValue(value);
		}
		_headers.put(headerName, value);
		return null;
	}

	@SuppressWarnings("unchecked")
	public <T> T removeHeader(String headerName) {
		Entry<String, T> entry = getHeaderEntry(headerName);
		if (entry != null) {
			return (T) _headers.remove(entry.getKey());
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
		if (charsetName != null) {
			Charset charset = Charset.forName(charsetName);
			_charset = charset != CHARSET_DEFAULT ? charset : null;
		} else {
			_charset = null;
		}
	}

	public void setSinkEncoding(String sinkEncoding) {
		_sinkEncoding = Charset.forName(sinkEncoding);
	}

	public String getSinkEncoding() {
		return (_sinkEncoding != null ? _sinkEncoding : getCharset()).name();
	}
	
	public Properties getSinkProperties() {
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

	public byte[] getBodyAsByteArray(Context context) throws TransformerException, IOException, XQException {
		byte[] ba;
		Charset charset = _charset;
		ByteArrayOutputStream bos;
		switch (_bodyType) {
		case DOM:
			writeRawTo(bos = new ByteArrayOutputStream(MTU), context);
			ba = bos.toByteArray();
			charset = _sinkEncoding;
			break;
		case STRING:
			ba = ((String) _body).getBytes(getSinkEncoding());
			charset = _sinkEncoding;
			break;
		case BYTES:
			return (byte[]) _body;
		case INPUT_STREAM:
			ba = StreamUtils.copy(getUncompressedInputStream());
			break;
		case XQ_ITEM:
			XQItem xqItem = (XQItem) _body;
			xqItem.writeItem(bos = new ByteArrayOutputStream(MTU), getSinkProperties());
			ba = bos.toByteArray();
			charset = _sinkEncoding;
			break;
		case INVALID:
			throw new IllegalStateException("Message is invalid");
		default:
			throw new IllegalStateException("BodyType not allowed: " + _bodyType);
		}
		return init(BodyType.BYTES, ba, charset);
	}

	public String getBodyAsString(Context context) throws TransformerException, IOException, XQException {
		String str;
		StringWriter sw;
		switch (_bodyType) {
		case DOM:
			transform(context.getIdenticalTransformer(), new StreamResult(sw = new StringWriter(MTU)));
			str = sw.toString();
			break;
		case STRING:
			return (String) _body;
		case BYTES:
			str = new String((byte[]) _body, getCharset());
			break;
		case INPUT_STREAM:
			getBodyAsByteArray(context);
			return getBodyAsString(context);
		case XQ_ITEM:
			XQItem xqItem = (XQItem) _body;
			xqItem.writeItem(sw = new StringWriter(MTU), getSinkProperties());
			str = sw.toString();
			break;
		case READER:
			StreamUtils.copy((Reader) _body, sw = new StringWriter(MTU));
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
		return init(BodyType.STRING, str, null);
	}

	public InputStream getBodyAsInputStream(Context context) throws TransformerException, IOException, XQException {
		switch (_bodyType) {
		case INPUT_STREAM:
			return getUncompressedInputStream();
		case XQ_ITEM:
			ByteArrayOutputStream bos = new ByteArrayOutputStream(MTU);
			XQItem xqItem = (XQItem) _body;
			xqItem.writeItem(bos, getSinkProperties());
			return init(BodyType.INPUT_STREAM, bos.getByteArrayInputStream(), _sinkEncoding);
		default:
			return new ByteArrayInputStream(getBodyAsByteArray(context));
		}
	}

	public Reader getBodyAsReader(Context context) throws TransformerException, IOException, XQException {
		switch (_bodyType) {
		case READER:
			return (Reader) _body;
		case INPUT_STREAM:
			return init(BodyType.READER, getInputStreamReader(), null);
		case XQ_ITEM:
			StringWriter sw = new StringWriter(MTU);
			XQItem xqItem = (XQItem) _body;
			xqItem.writeItem(sw, getSinkProperties());
			return sw.getStringReader();
		default:
			return new StringReader(getBodyAsString(context));
		}
	}

	public Source getBodyAsSource(Context context) throws IOException {
		Entry<String, String> contentType = getHeaderEntry(HTTP_HEADER_CONTENT_TYPE);
		if (contentType != null && isFastInfoset(contentType.getValue())) {
			InputStream is;
			switch (_bodyType) {
			case BYTES:
				is = new ByteArrayInputStream((byte[]) _body);
				break;
			case INPUT_STREAM:
				is = getUncompressedInputStream();
				break;
			default:
				throw new IllegalStateException("BodyType not allowed: " + _bodyType);
			}
			contentType.setValue(contentType.getValue().startsWith(HTTP_HEADER_CONTENT_TYPE_FI_SOAP11) ? SOAP_1_1_CONTENT_TYPE : SOAP_1_2_CONTENT_TYPE);
			return new SAXSource(context.getFastInfosetDeserializer().getFastInfosetReader(), new InputSource(is));
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
			init(BodyType.STRING, asXMLString((Exception) _body), null);
		case STRING:
			return new StreamSource(new StringReader((String) _body));
		case BYTES:
			_body = new ByteArrayInputStream((byte[]) _body);
			// nobreak
		case INPUT_STREAM:
			return init(BodyType.INVALID, new StreamSource(getInputStreamReader()), null);
		case READER:
			_bodyType = BodyType.INVALID;
			return new StreamSource((Reader) _body);
		default:
			throw new IllegalStateException("Message is invalid");
		}
	}

	private static String asXMLString(Exception e) {
		String xml = "<exception><message><![CDATA[" + e + "]]></message>";
		if (e.getCause() != null) {
			xml += "<cause><![CDATA[" + e.getCause() + "]]></cause>";
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
			return context.getPoolContext().getGlobalContext().getXMLInputFactory().createXMLStreamReader(getBodyAsSource(context));
		}
	}

	public boolean isSink() {
		return _bodyType == BodyType.RESULT || _bodyType == BodyType.OUTPUT_STREAM || _bodyType == BodyType.WRITER;
	}

	public boolean isStream() {
		return _bodyType == BodyType.INPUT_STREAM || _bodyType == BodyType.READER;
	}

	public boolean isEmpty() {
		switch (_bodyType) {
		case INVALID:
			return true;
		case STRING:
			return ((String) _body).isEmpty();
		case BYTES:
			return ((byte[]) _body).length == 0;
		default:
			return false;
		}
	}

	public Result getBodyAsSinkResult(Context context) throws Exception {
		if (isFastInfoset(this.<String> getHeader(HTTP_HEADER_CONTENT_TYPE))) {
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
				init(BodyType.WRITER, new OutputStreamWriter((OutputStream) _body, _sinkEncoding), null);
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
		os = getCompressedOutputStream(os);
		if (isFastInfoset(this.<String> getHeader(HTTP_HEADER_CONTENT_TYPE))) {
			SchemaAwareFastInfosetSerializer serializer = context.getResourceFactory(SchemaAwareFISerializerFactory.class).getResource(_schema);
			serializer.getFastInfosetSerializer().setOutputStream(os);
			serializer.getFastInfosetSerializer().setCharacterEncodingScheme(getSinkEncoding());
			writeTo(new SAXResult(serializer.getContentHandler()), context);
		} else {
			writeRawTo(os, context);
		}
		if (!isSink()) {
			init(BodyType.OUTPUT_STREAM, os, _sinkEncoding);
		}
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
			if (isSinkEncodingdifferent()) {
				StreamUtils.copy(getInputStreamReader(), init(BodyType.WRITER, new OutputStreamWriter(os, _sinkEncoding), null));
			} else {
				// writes compressed data through!
				StreamUtils.copy((InputStream) _body, init(BodyType.OUTPUT_STREAM, os, _sinkEncoding));
			}
			break;
		case READER:
			StreamUtils.copy((Reader) _body, init(BodyType.WRITER, new OutputStreamWriter(os, getSinkEncoding()), null));
			break;
		case XQ_SEQUENCE:
			XQSequence xqSequence = (XQSequence) _body;
			if (xqSequence.next()) {
				init(BodyType.XQ_ITEM, xqSequence.getItem(), null);
			} else {
				throw new IllegalStateException("Message already consumed");
			}
		case XQ_ITEM:
			XQItem xqItem = (XQItem) _body;
			xqItem.writeItem(os, getSinkProperties());
			break;
		case INVALID:
			throw new IllegalStateException("Message is invalid");
		default:
			throw new IllegalStateException("BodyType not allowed: " + _bodyType);
		}
	}

	public void closeBody() throws IOException {
		if (_bodyType == BodyType.OUTPUT_STREAM || _bodyType == BodyType.WRITER) {
			// necessary for filter streams
			((java.io.Closeable) _body).close();
			init(BodyType.INVALID, null, null);
		}
	}

	@Override
	public ESBMessage clone() throws CloneNotSupportedException {
		return (ESBMessage) super.clone();
	}

	// Should be the context of the Thread receiving this copy
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
				newBody = context.getXQDataFactory().createItem((XQItem) _body);
				break;
			default:
				newBody = _body;
				break;
			}
			clone = new ESBMessage(_bodyType, newBody, _charset);
		} else {
			clone = new ESBMessage(BodyType.INVALID, null);
		}
		clone.getHeaders().putAll(_headers);
		clone.getVariables().putAll(_variables);
		return clone;
	}

}
