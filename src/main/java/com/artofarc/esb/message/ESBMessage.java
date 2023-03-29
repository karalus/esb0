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
package com.artofarc.esb.message;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

import javax.json.JsonReader;
import javax.json.JsonValue;
import javax.json.JsonWriter;
import javax.json.stream.JsonGenerator;
import javax.mail.MessagingException;
import javax.mail.internet.MimeBodyPart;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.xquery.XQException;
import javax.xml.xquery.XQItem;
import javax.xml.xquery.XQItemAccessor;
import javax.xml.xquery.XQSequence;

import org.w3c.dom.Node;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import com.artofarc.esb.context.Context;
import static com.artofarc.esb.http.HttpConstants.*;
import com.artofarc.esb.resource.SchemaAwareFISerializerFactory;
import com.artofarc.util.*;

public final class ESBMessage implements Cloneable {

	public static final Charset CHARSET_DEFAULT = java.nio.charset.StandardCharsets.UTF_8;

	private static String normalize(String headerName) {
		return headerName.toLowerCase(Locale.ROOT).replace('_', '-');
	}

	private final Map<String, Map.Entry<String, Object>> _headers = new HashMap<>(32);
	private final Map<String, Object> _variables = new HashMap<>(32);
	private final Map<String, MimeBodyPart> _attachments = new LinkedHashMap<>();

	private BodyType _bodyType;
	private Object _body;
	private Charset _charset, _sinkEncoding;
	private String _contentEncoding, _contentType;
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
		if (body == null) {
			bodyType = BodyType.INVALID;
		} else if (bodyType == null) {
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

	public void clearHeadersExcept(Collection<String> normalizedHeaderNames) {
		if (normalizedHeaderNames.isEmpty()) {
			_headers.clear();
		} else {
			_headers.keySet().retainAll(normalizedHeaderNames);
		}
	}

	public Map<String, Object> getVariables() {
		return _variables;
	}

	public Map<String, MimeBodyPart> getAttachments() {
		return _attachments;
	}

	@SuppressWarnings("unchecked")
	public <T> T getHeader(String headerName) {
		Map.Entry<String, Object> entry = _headers.get(normalize(headerName));
		return entry != null ? (T) entry.getValue() : null;
	}

	public void putHeader(String headerName, Object value) {
		_headers.put(normalize(headerName), DataStructures.createEntry(headerName, value));
	}

	public void addHeader(String headerName, Object value) {
		String key = normalize(headerName);
		Map.Entry<String, Object> entry = _headers.get(key);
		if (entry != null) {
			_headers.put(key, DataStructures.createEntry(entry.getKey(), entry.getValue() + ", " + value));
		} else {
			_headers.put(key, DataStructures.createEntry(headerName, value));
		}
	}

	@SuppressWarnings("unchecked")
	public <T> T removeHeader(String headerName) {
		Map.Entry<String, ?> entry = _headers.remove(normalize(headerName));
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

	public void putVariable(String varName, Object value) {
		if (value != null) {
			_variables.put(varName, value);
		} else {
			_variables.remove(varName);
		}
	}

	public void putVariableIfNotNull(String varName, Object value) {
		if (value != null) {
			_variables.put(varName, value);
		}
	}

	public <T> T removeVariable(String varName) {
		@SuppressWarnings("unchecked")
		T result = (T) _variables.remove(varName);
		if (result == null) {
			throw new IllegalStateException("Variable not set: " + varName);
		}
		return result;
	}

	void addAttachment(String cid, MimeBodyPart bodyPart) {
		_attachments.put(cid, bodyPart);
	}

	public void addAttachment(String cid, String contentType, byte[] content, String filename) throws MessagingException {
		MimeBodyPart part = MimeHelper.createMimeBodyPart(cid, contentType, content, filename);
		part.setHeader(HTTP_HEADER_CONTENT_TRANSFER_ENCODING, "binary");
		addAttachment(cid, part);
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

	public String getContentEncoding() {
		return _contentEncoding;
	}

	public void setContentEncoding(String contentEncoding) {
		_contentEncoding = contentEncoding;
	}

	public String getContentType() {
		return _contentType;
	}

	public void setContentType(String contentType) {
		_contentType = contentType;
	}

	public boolean prepareContent() throws Exception {
		setContentEncoding(removeHeader(HTTP_HEADER_CONTENT_ENCODING));
		String contentType = getHeader(HTTP_HEADER_CONTENT_TYPE);
		_contentType = MimeHelper.parseContentType(this, contentType);
		if (_bodyType.hasCharset()) {
			setCharset(determineCharset(_contentType));
		} else {
			_charset = null;
		}
		return contentType != _contentType;
	}

	public void determineSinkContentType() {
		String contentType = getHeader(HTTP_HEADER_CONTENT_TYPE);
		if (contentType == null && _contentType != null) {
			putHeader(HTTP_HEADER_CONTENT_TYPE, contentType = _contentType);
		}
		if (needsCharset(contentType)) {
			putHeader(HTTP_HEADER_CONTENT_TYPE, contentType += "; " + HTTP_HEADER_CONTENT_TYPE_PARAMETER_CHARSET + getSinkEncoding());
		}
	}

	public void setSinkEncoding(String sinkEncoding) {
		_sinkEncoding = sinkEncoding != null ? Charset.forName(sinkEncoding) : null;
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

	private Properties getSinkProperties() {
		Properties props = getVariable(ESBConstants.serializationParameters);
		String encoding = null;
		if (props != null) {
			encoding = props.getProperty(OutputKeys.ENCODING);
		} else {
			props = new Properties();
		}
		if (encoding != null) {
			setSinkEncoding(encoding);
		} else {
			props.setProperty(OutputKeys.ENCODING, getSinkEncoding());
		}
		props.putIfAbsent(OutputKeys.INDENT, Context.XML_OUTPUT_INDENT);
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

	private boolean isOutputCompressed() {
		final String contentEncoding = getHeader(HTTP_HEADER_CONTENT_ENCODING);
		return contentEncoding != null && !contentEncoding.equals(getContentEncoding());
	}

	private OutputStream getCompressedOutputStream(OutputStream outputStream) throws IOException {
		final String contentEncoding = getHeader(HTTP_HEADER_CONTENT_ENCODING);
		if (contentEncoding != null) {
			if (contentEncoding.equals(getContentEncoding()) && !isSinkEncodingdifferent()) {
				setContentEncoding(null);
			} else {
				switch (contentEncoding) {
				case "gzip":
					return new GZIPOutputStream(outputStream, IOUtils.MTU);
				case "deflate":
					return new DeflaterOutputStream(outputStream);
				default:
					throw new IOException("Content-Encoding not supported: " + contentEncoding);
				}
			}
		}
		return outputStream;
	}

	private InputStream getUncompressedInputStream(InputStream inputStream) throws IOException {
		final String contentEncoding = getContentEncoding();
		if (contentEncoding != null) {
			switch (contentEncoding) {
			case "gzip":
				inputStream = new GZIPInputStream(inputStream, IOUtils.MTU);
				break;
			case "deflate":
				inputStream = new InflaterInputStream(inputStream);
				break;
			case "identity":
				break;
			default:
				throw new IOException("Unsupported content encoding " + contentEncoding);
			}
			setContentEncoding(null);
		}
		return inputStream;
	}

	private InputStreamReader getInputStreamReader(InputStream inputStream) throws IOException {
		if (isBinary(_contentType)) {
			throw new IllegalStateException("Message is binary: " + _contentType);
		}
		return new InputStreamReader(getUncompressedInputStream(inputStream), getCharset());
	}

	public byte[] getBodyAsByteArray(Context context) throws Exception {
		byte[] ba;
		Charset charset = _charset;
		ByteArrayOutputStream bos;
		switch (_bodyType) {
		case DOM:
		case SOURCE:
		case JSON_VALUE:
			writeRawTo(bos = new ByteArrayOutputStream(), context);
			ba = bos.toByteArray();
			charset = _sinkEncoding;
			break;
		case READER:
			getBodyAsString(context);
			// nobreak
		case STRING:
			ba = ((String) _body).getBytes(getSinkEncodingCharset());
			charset = _sinkEncoding;
			break;
		case BYTES:
			return (byte[]) _body;
		case INPUT_STREAM:
			ba = IOUtils.toByteArray(getUncompressedInputStream((InputStream) _body));
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

	public String getBodyAsString(Context context) throws Exception {
		String str;
		StringBuilderWriter sw;
		switch (_bodyType) {
		case DOM:
			_body = new DOMSource((Node) _body);
		case SOURCE:
			context.transform((Source) _body, new StreamResult(sw = new StringBuilderWriter()), getVariable(ESBConstants.serializationParameters));
			str = sw.toString();
			break;
		case STRING:
			return (String) _body;
		case INPUT_STREAM:
			getBodyAsByteArray(context);
			// nobreak
		case BYTES:
			if (isBinary(_contentType)) {
				throw new IllegalStateException("Message is binary: " + _contentType);
			}
			str = new String((byte[]) _body, getCharset());
			break;
		case XQ_ITEM:
			XQItem xqItem = (XQItem) _body;
			xqItem.writeItem(sw = new StringBuilderWriter(), getSinkProperties());
			str = sw.toString();
			break;
		case READER:
			if (_body instanceof StringBuilderReader) {
				str = _body.toString();
			} else {
				IOUtils.copy((Reader) _body, sw = new StringBuilderWriter());
				str = sw.toString();
			}
			break;
		case EXCEPTION:
			Exception e = (Exception) _body;
			String contentType = getHeader(HTTP_HEADER_CONTENT_TYPE);
			if (contentType == null || isNotXML(contentType)) {
				str = DataStructures.asString(e);
			} else {
				str = DataStructures.asXMLString(e);
			}
			break;
		case JSON_VALUE:
			str = _body.toString();
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
	public InputStream getBodyAsInputStream(Context context) throws Exception {
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

	public Reader getBodyAsReader(Context context) throws Exception {
		switch (_bodyType) {
		case READER:
			return (Reader) _body;
		case INPUT_STREAM:
			return init(BodyType.READER, getInputStreamReader((InputStream) _body), null);
		case XQ_ITEM:
			StringBuilderWriter sw = new StringBuilderWriter();
			XQItem xqItem = (XQItem) _body;
			xqItem.writeItem(sw, getSinkProperties());
			return sw.getReader();
		default:
			return new StringReader(getBodyAsString(context));
		}
	}

	@SuppressWarnings("unchecked")
	public <T extends JsonValue> T getBodyAsJsonValue(Context context) throws Exception {
		if (_bodyType != BodyType.JSON_VALUE) {
			try (JsonReader jsonReader = JsonFactoryHelper.JSON_READER_FACTORY.createReader(getBodyAsReader(context))) {
				init(BodyType.JSON_VALUE, jsonReader.readValue(), null);
			}
		}
		return (T) _body;
	}

	public Source getBodyAsSource(Context context) throws IOException, XQException {
		if (isFastInfoset(_contentType)) {
			InputSource is;
			switch (_bodyType) {
			case SOURCE:
				is = SAXSource.sourceToInputSource((Source) _body);
				break;
			case BYTES:
				is = new InputSource(new ByteArrayInputStream((byte[]) _body));
				break;
			case INPUT_STREAM:
				is = new InputSource(getUncompressedInputStream((InputStream) _body));
				break;
			default:
				throw new IllegalStateException("BodyType not allowed: " + _bodyType);
			}
			putHeader(HTTP_HEADER_CONTENT_TYPE, _contentType.startsWith(HTTP_HEADER_CONTENT_TYPE_FI_SOAP11) ? HTTP_HEADER_CONTENT_TYPE_SOAP11 : HTTP_HEADER_CONTENT_TYPE_SOAP12);
			return new SAXSource(context.getFastInfosetDeserializer(), is);
		}
		return getBodyAsSourceInternal();
	}

	private Source getBodyAsSourceInternal() throws IOException, XQException {
		switch (_bodyType) {
		case SOURCE:
			return (Source) _body;
		case XQ_SEQUENCE:
			extractItemFromSequence();
			// nobreak
		case XQ_ITEM:
			XQItem xqItem = (XQItem) _body;
			return new DOMSource(xqItem.getNode());
		case DOM:
			return new DOMSource((Node) _body);
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

	private void extractItemFromSequence() throws XQException {
		XQSequence xqSequence = (XQSequence) _body;
		if (xqSequence.next()) {
			init(BodyType.XQ_ITEM, xqSequence.getItem(), null);
		} else {
			throw new IllegalStateException("Sequence prematurely ended. Could not get body");
		}
	}

	public boolean isSink() {
		return _bodyType == BodyType.RESULT || _bodyType == BodyType.OUTPUT_STREAM || _bodyType == BodyType.WRITER;
	}

	public boolean isStream() {
		return _bodyType == BodyType.INPUT_STREAM || _bodyType == BodyType.READER;
	}

	public boolean isEmpty() throws IOException {
		switch (_bodyType) {
		case INVALID:
			return true;
		case STRING:
			return ((String) _body).isEmpty();
		case BYTES:
			return ((byte[]) _body).length == 0;
		case INPUT_STREAM:
			InputStream is = (InputStream) _body;
			if (is.available() > 0) {
				return false;
			}
			if (is.markSupported()) {
				is.mark(1);
				if (is.read() < 0) {
					is.close();
					init(BodyType.STRING, "", null);
					return true;
				} else {
					is.reset();
					return false;
				}
			} else {
				int b = is.read();
				if (b < 0) {
					is.close();
					init(BodyType.STRING, "", null);
					return true;
				} else {
					PushbackInputStream pis = new PushbackInputStream(is);
					pis.unread(b);
					_body = pis;
					return false;
				}
			}
		default:
			return false;
		}
	}

	public Long getOutputLength() throws IOException {
		final long length;
		if (_bodyType == BodyType.BYTES) {
			length = ((byte[]) _body).length;
		} else if (_bodyType == BodyType.INPUT_STREAM && _body instanceof IOUtils.PredictableInputStream) {
			length = ((IOUtils.PredictableInputStream) _body).length();
		} else {
			return isEmpty() ? 0L : null;
		}
		if (length > 0 && (isSinkEncodingdifferent() || isFastInfoset(getHeader(HTTP_HEADER_CONTENT_TYPE))) || isOutputCompressed()) {
			return null;
		}
		return length;
	}

	private void prepareFI(Context context) throws IOException {
		final String contentType = getHeader(HTTP_HEADER_CONTENT_TYPE);
		if (isFastInfoset(contentType)) {
			if (_bodyType != BodyType.OUTPUT_STREAM) {
				throw new IllegalStateException("Message cannot be converted to FastInfoset: " + _bodyType);
			}
			SchemaAwareFastInfosetSerializer serializer = context.getResourceFactory(SchemaAwareFISerializerFactory.class).getResource(_schema);
			init(BodyType.RESULT, new SAXResult(serializer.getContentHandler(getCompressedOutputStream((OutputStream) _body), getSinkEncoding())), null);
			setContentType(contentType);
		}
	}

	public Result createResultFromBodyAsSink(Context context) throws Exception {
		prepareFI(context);
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

	public JsonGenerator createJsonGeneratorFromBodyAsSink() throws IOException {
		switch (_bodyType) {
		case OUTPUT_STREAM:
			_body = getCompressedOutputStream((OutputStream) _body);
			return JsonFactoryHelper.JSON_GENERATOR_FACTORY.createGenerator((OutputStream) _body, getSinkEncodingCharset());
		case WRITER:
			return JsonFactoryHelper.JSON_GENERATOR_FACTORY.createGenerator((Writer) _body);
		default:
			throw new IllegalStateException("Message cannot be converted to JsonGenerator: " + _bodyType);
		}
	}

	public void writeToSAX(ContentHandler contentHandler, Context context) throws XQException, TransformerException, IOException, SAXException {
		switch (_bodyType) {
		case XQ_SEQUENCE:
			extractItemFromSequence();
			// nobreak
		case XQ_ITEM:
			XQItem xqItem = (XQItem) _body;
			xqItem.writeItemToResult(new SAXResult(contentHandler));
			break;
		default:
			Source source = getBodyAsSource(context);
			if (source instanceof SAXSource) {
				SAXSource saxSource = (SAXSource) source;
				XMLReader xmlReader = saxSource.getXMLReader();
				if (xmlReader != null) {
					xmlReader.setContentHandler(contentHandler);
					xmlReader.parse(saxSource.getInputSource());
					break;
				}
			}
			context.transformRaw(source, new SAXResult(contentHandler));
			break;
		}
	}

	public void writeTo(DOMResult result, Context context) throws XQException, TransformerException, IOException {
		if (_bodyType == BodyType.XQ_ITEM) {
			XQItem xqItem = (XQItem) _body;
			result.setNode(xqItem.getNode());
		} else {
			context.transformRaw(getBodyAsSource(context), result);
		}
	}

	public void writeTo(OutputStream os, Context context) throws Exception {
		os = getCompressedOutputStream(os);
		if (isFastInfoset(getHeader(HTTP_HEADER_CONTENT_TYPE))) {
			SchemaAwareFastInfosetSerializer serializer = context.getResourceFactory(SchemaAwareFISerializerFactory.class).getResource(_schema);
			writeToSAX(serializer.getContentHandler(os, getSinkEncoding()), context);
		} else {
			writeRawTo(os, context);
		}
		if (!isSink()) {
			init(BodyType.OUTPUT_STREAM, os, _sinkEncoding);
		}
	}

	public void writeRawTo(OutputStream os, Context context) throws Exception {
		switch (_bodyType) {
		case DOM:
			_body = new DOMSource((Node) _body);
		case SOURCE:
			try (Writer writer = new OutputStreamWriter(os, getSinkEncodingCharset())) {
				context.transform((Source) _body, new StreamResult(writer), getVariable(ESBConstants.serializationParameters));
			}
			init(BodyType.INVALID, null, null);
			break;
		case STRING:
			String str = (String) _body;
			if (str.length() > 0) {
				try (Writer writer = new OutputStreamWriter(os, getSinkEncodingCharset())) {
					writer.write(str);
				}
			}
			break;
		case BYTES:
			if (isSinkEncodingdifferent()) {
				try (Reader reader = new InputStreamReader(new ByteArrayInputStream((byte[]) _body), getCharset()); Writer writer = new OutputStreamWriter(os, getSinkEncodingCharset())) {
					IOUtils.copy(reader, writer);
				}
			} else {
				os.write((byte[]) _body);
			}
			break;
		case INPUT_STREAM:
			if (isSinkEncodingdifferent()) {
				try (Reader reader = getInputStreamReader((InputStream) _body); Writer writer = new OutputStreamWriter(os, getSinkEncodingCharset())) {
					IOUtils.copy(reader, writer);
				}
			} else {
				// writes compressed data through!
				try (InputStream inputStream = getUncompressedInputStream((InputStream) _body)) {
					IOUtils.copy(inputStream, os);
				}
			}
			init(BodyType.INVALID, null, null);
			break;
		case READER:
			try (Writer writer = new OutputStreamWriter(os, getSinkEncodingCharset())) {
				IOUtils.copy((Reader) _body, writer);
			}
			init(BodyType.INVALID, null, null);
			break;
		case XQ_SEQUENCE:
			extractItemFromSequence();
			// nobreak
		case XQ_ITEM:
			XQItem xqItem = (XQItem) _body;
			xqItem.writeItem(os, getSinkProperties());
			break;
		case JSON_VALUE:
			try (JsonWriter jsonWriter = JsonFactoryHelper.JSON_WRITER_FACTORY.createWriter(os, getSinkEncodingCharset())) {
				jsonWriter.write((JsonValue) _body);
			}
			break;
		case INVALID:
			throw new IllegalStateException("Message is invalid");
		default:
			throw new IllegalStateException("BodyType not allowed: " + _bodyType);
		}
	}

	public void writeItemToSink(XQItemAccessor xqItem, Context context) throws Exception {
		prepareFI(context);
		switch (_bodyType) {
		case RESULT:
			xqItem.writeItemToResult((Result) _body);
			break;
		case OUTPUT_STREAM:
			_body = getCompressedOutputStream((OutputStream) _body);
			xqItem.writeItem((OutputStream) _body, getSinkProperties());
			break;
		case WRITER:
			xqItem.writeItem((Writer) _body, getSinkProperties());
			break;
		default:
			throw new IllegalStateException("Message cannot be converted to Result: " + _bodyType);
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
		} else if (_body instanceof StreamResult) {
			StreamResult streamResult = getBody();
			if (streamResult.getOutputStream() != null) {
				streamResult.getOutputStream().close();
			} else if (streamResult.getWriter() != null) {
				streamResult.getWriter().close();
			}
		}
	}

	@Override
	public ESBMessage clone() throws CloneNotSupportedException {
		return (ESBMessage) super.clone();
	}

	public Object cloneBody(Context context, boolean singleUse) throws Exception {
		final Object newBody;
		switch (_bodyType) {
		case INPUT_STREAM:
			if (singleUse && _body instanceof ByteArrayInputStream) {
				newBody = ((ByteArrayInputStream) _body).clone();
			} else {
				newBody = getBodyAsByteArray(context);
			}
			break;
		case READER:
			if (singleUse && _body instanceof StringBuilderReader) {
				newBody = ((StringBuilderReader) _body).clone();
			} else {
				newBody = getBodyAsString(context);
			}
			break;
		case SOURCE:
			newBody = init(BodyType.XQ_ITEM, context.getXQDataFactory().createItemFromDocument((Source) _body, null), null);
			break;
		case XQ_SEQUENCE:
			extractItemFromSequence();
			// nobreak
		case XQ_ITEM:
			// Must outlive current context
			newBody = context.getGlobalContext().getXQDataFactory().createItem((XQItem) _body);
			break;
		case RESULT:
		case OUTPUT_STREAM:
		case WRITER:
			throw new IllegalStateException("Cannot clone a sink");
		default:
			newBody = _body;
			break;
		}
		return newBody;
	}

	public ESBMessage copy(Context context, boolean withBody, boolean withHeaders, boolean withAttachments) throws Exception {
		final ESBMessage clone;
		if (withBody) {
			Object newBody = cloneBody(context, true);
			clone = new ESBMessage(_bodyType, newBody, _charset);
		} else {
			clone = new ESBMessage(BodyType.INVALID, null, null);
		}
		if (withHeaders) {
			clone._headers.putAll(_headers);
		}
		if (withAttachments) {
			clone._attachments.putAll(_attachments);
		}
		for (Map.Entry<String, Object> entry : _variables.entrySet()) {
			if (entry.getKey() == ESBConstants.initialTimestamp) {
				clone._variables.put(ESBConstants.initialTimestampOrigin, entry.getValue());
			} else if (!entry.getKey().startsWith("_")) {
				clone._variables.put(entry.getKey(), entry.getValue());
			}
		}
		return clone;
	}

}
