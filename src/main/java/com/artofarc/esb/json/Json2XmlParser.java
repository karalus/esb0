package com.artofarc.esb.json;

import java.nio.charset.Charset;
import java.util.ArrayDeque;

import javax.json.Json;
import javax.json.stream.JsonParser;
import javax.json.stream.JsonParserFactory;
import javax.xml.XMLConstants;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import com.artofarc.util.XMLFilterBase;

public class Json2XmlParser extends XMLFilterBase {

	private static final JsonParserFactory jsonParserFactory = Json.createParserFactory(null);
	private static final AttributesImpl emptyAtts = new AttributesImpl();

	private final boolean _createDocumentEvents;

	public Json2XmlParser(boolean createDocumentEvents) {
		_createDocumentEvents = createDocumentEvents;
	}

	private static final class Parser implements Locator {

		private final JsonParser jsonParser;
		private final InputSource _source;

		private Parser(InputSource source) throws SAXException {
			if (source.getByteStream() != null) {
				if (source.getEncoding() != null) {
					jsonParser = jsonParserFactory.createParser(source.getByteStream(), Charset.forName(source.getEncoding()));
				} else {
					jsonParser = jsonParserFactory.createParser(source.getByteStream());
				}
			} else if (source.getCharacterStream() != null) {
				jsonParser = jsonParserFactory.createParser(source.getCharacterStream());
			} else {
				throw new SAXException("InputSource must provide a stream");
			}
			_source = source;
		}

		@Override
		public String getPublicId() {
			return _source.getPublicId();
		}

		@Override
		public String getSystemId() {
			return _source.getSystemId();
		}

		@Override
		public int getLineNumber() {
			return (int) jsonParser.getLocation().getLineNumber();
		}

		@Override
		public int getColumnNumber() {
			return (int) jsonParser.getLocation().getColumnNumber();
		}
	}

	private static final class Element {
		String uri, localName, qName;
	}

	private final ArrayDeque<Element> stack = new ArrayDeque<>();

	private void _startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
		Element e = new Element();
		getContentHandler().startElement(e.uri = uri, e.localName = localName, e.qName = qName, atts);
		stack.push(e);
	}

	private void _endElement() throws SAXException {
		Element e = stack.pop();
		getContentHandler().endElement(e.uri, e.localName, e.qName);
	}

	private void writeValue(String key, String value) throws SAXException {
		final ContentHandler ch = getContentHandler();
		ch.startElement(XMLConstants.NULL_NS_URI, key, key, emptyAtts);
		ch.characters(value.toCharArray(), 0, value.length());
		ch.endElement(XMLConstants.NULL_NS_URI, key, key);
	}

	@Override
	public void parse(InputSource source) throws SAXException {
		final ContentHandler ch = getContentHandler();
		if (ch == null) {
			throw new SAXException("ContentHandler not set");
		}
		Parser parser = new Parser(source);
		setDocumentLocator(parser);
		try (JsonParser jsonParser = parser.jsonParser) {
			if (_createDocumentEvents) ch.startDocument();
			String keyName = "root";
			while (jsonParser.hasNext()) {
				switch (jsonParser.next()) {
				case START_OBJECT:
					_startElement(XMLConstants.NULL_NS_URI, keyName, keyName, emptyAtts);
					break;
				case END_OBJECT:
					_endElement();
					break;
				case START_ARRAY:
					break;
				case END_ARRAY:
					break;
				case KEY_NAME:
					keyName = jsonParser.getString();
					break;
				case VALUE_STRING:
				case VALUE_NUMBER:
					writeValue(keyName, jsonParser.getString());
					break;
				case VALUE_NULL:
					break;
				case VALUE_TRUE:
					writeValue(keyName, "true");
					break;
				case VALUE_FALSE:
					writeValue(keyName, "false");
					break;
				default:
					throw new IllegalStateException();
				}
				
			}
			if (_createDocumentEvents) ch.endDocument();
		}
	}

}
