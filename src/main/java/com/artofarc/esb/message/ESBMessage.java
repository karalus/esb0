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
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

import javax.json.Json;
import javax.json.stream.JsonGenerator;
import javax.json.stream.JsonGeneratorFactory;
import javax.mail.MessagingException;
import javax.mail.internet.MimeBodyPart;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.UnmarshallerHandler;
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

import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.artofarc.esb.context.Context;
import static com.artofarc.esb.http.HttpConstants.*;

import com.artofarc.esb.resource.SchemaAwareFISerializerFactory;
import com.artofarc.util.ByteArrayOutputStream;
import com.artofarc.util.Collections;
import com.artofarc.util.XMLFilterBase;
import com.artofarc.util.SchemaAwareFastInfosetSerializer;
import com.artofarc.util.IOUtils;
import com.artofarc.util.StringWriter;

public final class ESBMessage implements Cloneable {

	public static final Charset CHARSET_DEFAULT = java.nio.charset.StandardCharsets.UTF_8;
	public static final int MTU = IOUtils.MTU;
	private static final String XML_OUTPUT_INDENT = System.getProperty("esb0.xmlOutputIndent", "yes");
	private static final JsonGeneratorFactory JSON_GENERATOR_FACTORY;
	private static final Map<String, String> HEADER_NAMES = new ConcurrentHashMap<>(256); 

	static {
		Map<String, Object> config = new HashMap<>();
		if (Boolean.parseBoolean(System.getProperty("esb0.jsonPrettyPrinting"))) {
			config.put(JsonGenerator.PRETTY_PRINTING, true);
		}
		JSON_GENERATOR_FACTORY = Json.createGeneratorFactory(config);
	}

	private static String toLowerCase(String headerName) {
		String result = HEADER_NAMES.get(headerName);
		if (result == null) {
			result = headerName.toLowerCase();
			HEADER_NAMES.put(headerName, result);
		}
		return result;
	}

	private final HashMap<String, Map.Entry<String, Object>> _headers = new HashMap<>();
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
		return "ESBMessage [_bodyType=" + _bodyType + ", _charsetName=" + _charset + ", _headers=" + _headers.values() + ", _variables=" + _variables + "]";
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

	public Collection<Map.Entry<String, Object>> getHeaders() {
		return _headers.values();
	}

	public void clearHeaders() {
		_headers.clear();
	}

	public Map<String, Object> getVariables() {
		return _variables;
	}

	public Map<String, MimeBodyPart> getAttachments() {
		return _attachments;
	}

	@SuppressWarnings("unchecked")
	public <T> T getHeader(String headerName) {
		Map.Entry<String, Object> entry = _headers.get(toLowerCase(headerName));
		return entry != null ? (T) entry.getValue() : null;
	}

	public void putHeader(String headerName, Object value) {
		_headers.put(toLowerCase(headerName), Collections.createEntry(headerName, value));
	}

	@SuppressWarnings("unchecked")
	public <T> T removeHeader(String headerName) {
		Map.Entry<String, ?> entry = _headers.remove(toLowerCase(headerName));
		return entry != null ? (T) entry.getValue() : null;
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

	public void addAttachment(String cid, MimeBodyPart bodyPart) {
		// remove angle brackets (https://tools.ietf.org/html/rfc2392)
		_attachments.put(cid.substring(1, cid.length() - 1), bodyPart);
	}

	public void addAttachment(String contentID, String contentType, byte[] content, String filename) throws MessagingException {
		String cid = '<' + contentID + '>';
		addAttachment(cid, MimeHelper.createMimeBodyPart(cid, contentType, content, filename));
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

	private Charset getSinkEncodingCharset() {
		return _sinkEncoding != null ? _sinkEncoding : getCharset();
	}

	public String getSinkEncoding() {
		return getSinkEncodingCharset().name();
	}

	private boolean isSinkEncodingdifferent() {
		return _sinkEncoding != null && _sinkEncoding != getCharset();
	}

	public Properties getSinkProperties() {
		Properties props = new Properties();
		props.setProperty(OutputKeys.ENCODING, getSinkEncoding());
		props.setProperty(OutputKeys.INDENT, XML_OUTPUT_INDENT);
		return props;
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

	private InputStream getUncompressedInputStream(InputStream inputStream) throws IOException {
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

	private InputStreamReader getInputStreamReader(InputStream inputStream) throws IOException {
		return new InputStreamReader(getUncompressedInputStream(inputStream), getCharset());
	}

	public byte[] getBodyAsByteArray(Context context) throws TransformerException, IOException, XQException {
		byte[] ba;
		Charset charset = _charset;
		ByteArrayOutputStream bos;
		switch (_bodyType) {
		case DOM:
			writeRawTo(bos = new ByteArrayOutputStream(), context);
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
			ba = IOUtils.copy(getUncompressedInputStream((InputStream) _body));
			break;
		case XQ_ITEM:
			XQItem xqItem = (XQItem) _body;
			xqItem.writeItem(bos = new ByteArrayOutputStream(), getSinkProperties());
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
			transform(context.getIdenticalTransformer(), new StreamResult(sw = new StringWriter()));
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
			xqItem.writeItem(sw = new StringWriter(), getSinkProperties());
			str = sw.toString();
			break;
		case READER:
			if (_body instanceof com.artofarc.util.StringReader) {
				str = _body.toString();
			} else {
				IOUtils.copy((Reader) _body, sw = new StringWriter());
				str = sw.toString();
			}
			break;
		case EXCEPTION:
			Exception e = (Exception) _body;
			String contentType = getHeader(HTTP_HEADER_CONTENT_TYPE);
			if (isNotSOAP(contentType)) {
				str = e.getCause() != null ? e + "\nCause: " + e.getCause() : e.toString();
			} else {
				str = asXMLString(e);
			}
			break;
		case INVALID:
			throw new IllegalStateException("Message is invalid");
		default:
			throw new IllegalStateException("BodyType not allowed: " + _bodyType);
		}
		return init(BodyType.STRING, str, null);
	}

	/**
	 * @param context Can be null if we don't expect BodyType DOM
	 */
	public InputStream getBodyAsInputStream(Context context) throws TransformerException, IOException, XQException {
		switch (_bodyType) {
		case INPUT_STREAM:
			return getUncompressedInputStream((InputStream) _body);
		case XQ_ITEM:
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
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
			return init(BodyType.READER, getInputStreamReader((InputStream) _body), null);
		case XQ_ITEM:
			StringWriter sw = new StringWriter();
			XQItem xqItem = (XQItem) _body;
			xqItem.writeItem(sw, getSinkProperties());
			return sw.getStringReader();
		default:
			return new StringReader(getBodyAsString(context));
		}
	}

	public Source getBodyAsSource(Context context) throws IOException, XQException {
		String contentType = getHeader(HTTP_HEADER_CONTENT_TYPE);
		if (isFastInfoset(contentType)) {
			InputStream is;
			switch (_bodyType) {
			case BYTES:
				is = new ByteArrayInputStream((byte[]) _body);
				break;
			case INPUT_STREAM:
				is = getUncompressedInputStream((InputStream) _body);
				break;
			default:
				throw new IllegalStateException("BodyType not allowed: " + _bodyType);
			}
			putHeader(HTTP_HEADER_CONTENT_TYPE, contentType.startsWith(HTTP_HEADER_CONTENT_TYPE_FI_SOAP11) ? SOAP_1_1_CONTENT_TYPE : SOAP_1_2_CONTENT_TYPE);
			return new SAXSource(context.getFastInfosetDeserializer().getFastInfosetReader(), new InputSource(is));
		}
		return getBodyAsSourceInternal();
	}

	private Source getBodyAsSourceInternal() throws IOException, XQException {
		switch (_bodyType) {
		case SOURCE:
			return (Source) _body;
		case XQ_ITEM:
			XQItem xqItem = (XQItem) _body;
			return new DOMSource(xqItem.getNode());
		case DOM:
			return new DOMSource((Node) _body);
		case EXCEPTION:
			init(BodyType.STRING, asXMLString((Exception) _body), null);
		case STRING:
			return new StreamSource(new StringReader((String) _body));
		case BYTES:
			_body = new ByteArrayInputStream((byte[]) _body);
			// nobreak
		case INPUT_STREAM:
			StreamSource source = new StreamSource();
			if (_charset != null) {
				source.setReader(getInputStreamReader((InputStream) _body));
			} else {
				source.setInputStream(getUncompressedInputStream((InputStream) _body));
			}
			return init(BodyType.INVALID, source, null);
		case READER:
			_bodyType = BodyType.INVALID;
			return new StreamSource((Reader) _body);
		default:
			throw new IllegalStateException("Message is invalid");
		}
	}

	public static String asXMLString(Exception e) {
		String xml = "<exception><message><![CDATA[" + e + "]]></message>";
		if (e.getCause() != null) {
			xml += "<cause><![CDATA[" + e.getCause() + "]]></cause>";
		}
		xml += "</exception>";
		return xml;
	}

	public Object unmarshal(Context context, Unmarshaller unmarshaller) throws XQException, IOException, TransformerException, JAXBException {
		// Needed because neither Saxon DOM Element nor Saxon XMLStreamReader work with MOXy
		UnmarshallerHandler unmarshallerHandler;
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
			XQItem xqItem = (XQItem) _body;
			unmarshallerHandler = unmarshaller.getUnmarshallerHandler();
			xqItem.writeItemToSAX(unmarshallerHandler);
			return unmarshallerHandler.getResult();
		case DOM:
			unmarshallerHandler = unmarshaller.getUnmarshallerHandler();
			context.getIdenticalTransformer().transform(new DOMSource((Node) _body), new SAXResult(unmarshallerHandler));
			return unmarshallerHandler.getResult();
		default:
			return unmarshaller.unmarshal(getBodyAsSource(context));
		}
	}

	public <T> JAXBElement<T> unmarshal(Context context, Unmarshaller unmarshaller, Class<T> declaredType) throws XQException, IOException, JAXBException {
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
			XQItem xqItem = (XQItem) _body;
			return unmarshaller.unmarshal(xqItem.getItemAsStream(), declaredType);
		default:
			return unmarshaller.unmarshal(getBodyAsSource(context), declaredType);
		}
	}

	public void marshal(Context context, final Marshaller marshaller, final Object jaxbElement) throws XQException {
		SAXSource source = new SAXSource(new XMLFilterBase() {

			@Override
			public void parse(InputSource source) throws SAXException {
				try {
					marshaller.marshal(jaxbElement, getContentHandler());
				} catch (JAXBException e) {
					throw new SAXException(e);
				}
			}
		}, null);
		init(BodyType.XQ_ITEM, context.getXQDataFactory().createItemFromDocument(source, null), null);
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

	public JsonGenerator getBodyAsJsonGenerator() throws IOException {
		switch (_bodyType) {
		case OUTPUT_STREAM:
			_body = getCompressedOutputStream((OutputStream) _body);
			return JSON_GENERATOR_FACTORY.createGenerator((OutputStream) _body, getSinkEncodingCharset());
		case WRITER:
			return JSON_GENERATOR_FACTORY.createGenerator((Writer) _body);
		default:
			throw new IllegalStateException("Message cannot be converted to JsonGenerator: " + _bodyType);
		}
	}

	private void transform(Transformer transformer, Result result) throws TransformerException, XQException, IOException {
		Source source;
		switch (_bodyType) {
		case SOURCE:
			source = (Source) _body;
			break;
		case DOM:
			source = new DOMSource((Node) _body);
			break;
		case STRING:
			source = new StreamSource(new StringReader((String) _body));
			break;
		case BYTES:
			source = new StreamSource(new ByteArrayInputStream((byte[]) _body));
			break;
		case INPUT_STREAM:
			_bodyType = BodyType.INVALID;
			source = new StreamSource(getInputStreamReader((InputStream) _body));
			break;
		case READER:
			source = new StreamSource((Reader) _body);
			break;
		case XQ_SEQUENCE:
			XQSequence xqSequence = (XQSequence) _body;
			if (xqSequence.next()) {
				init(BodyType.XQ_ITEM, xqSequence.getItem(), null);
			} else {
				throw new IllegalStateException("Message already consumed");
			}
			// nobreak
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
				IOUtils.copy(getInputStreamReader((InputStream) _body), init(BodyType.WRITER, new OutputStreamWriter(os, _sinkEncoding), null));
			} else {
				// writes compressed data through!
				IOUtils.copy((InputStream) _body, os);
			}
			break;
		case READER:
			IOUtils.copy((Reader) _body, init(BodyType.WRITER, new OutputStreamWriter(os, getSinkEncoding()), null));
			break;
		case XQ_SEQUENCE:
			XQSequence xqSequence = (XQSequence) _body;
			if (xqSequence.next()) {
				init(BodyType.XQ_ITEM, xqSequence.getItem(), null);
			} else {
				throw new IllegalStateException("Message already consumed");
			}
			// nobreak
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

	public void copyFrom(InputStream inputStream) throws IOException {
		switch (_bodyType) {
		case OUTPUT_STREAM:
			IOUtils.copy(getUncompressedInputStream(inputStream), (OutputStream) _body);
			break;
		case WRITER:
			IOUtils.copy(getInputStreamReader(inputStream), (Writer) _body);
			break;
		default:
			throw new IllegalStateException("Message cannot be copied to: " + _bodyType);
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

	/**
	 * @param context Should be the context of the Thread receiving this copy
	 */
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
		clone._headers.putAll(_headers);
		for (Map.Entry<String, Object> entry : _variables.entrySet()) {
			if (entry.getKey() == ESBConstants.initialTimestamp) {
				clone._variables.put(ESBConstants.initialTimestampOrigin, entry.getValue());
			} else {
				clone._variables.put(entry.getKey(), entry.getValue());
			}
		}
		return clone;
	}

	public static void dumpKeyValues(Context context, Collection<Map.Entry<String, Object>> keyValues, Writer logWriter) throws IOException, TransformerException {
		logWriter.write('{');
		for (Iterator<Map.Entry<String, Object>> iter = keyValues.iterator(); iter.hasNext();) {
			Map.Entry<String, Object> entry = iter.next();
			logWriter.write(entry.getKey() + "=");
			if (entry.getValue() instanceof Node) {
				context.getIdenticalTransformer().transform(new DOMSource((Node) entry.getValue()), new StreamResult(logWriter));
			} else {
				logWriter.write(String.valueOf(entry.getValue()));
			}
			if (iter.hasNext()) {
				logWriter.write(", ");
			}
		}
		logWriter.write('}');
	}

}
