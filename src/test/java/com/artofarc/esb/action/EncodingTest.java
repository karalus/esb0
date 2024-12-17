package com.artofarc.esb.action;

import static org.junit.Assert.*;

import java.io.File;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Properties;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.artofarc.esb.AbstractESBTest;
import com.artofarc.esb.ConsumerPort;

import static com.artofarc.esb.http.HttpConstants.*;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.StringWrapper;
import com.artofarc.util.URLUtils;


public class EncodingTest extends AbstractESBTest {

	@Test
	public void testDecodeRequest() throws Exception {
		ESBMessage message = new ESBMessage(BodyType.BYTES,
				"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<test>Hell�</test>".getBytes(ESBMessage.CHARSET_DEFAULT));
		message.putHeader(HTTP_HEADER_CONTENT_TYPE, "text/xml");
		message.setCharset(determineCharset("text/xml"));
		Action action = createAssignAction("result", "test/text()");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(action);
		action.setNextAction(new DumpAction());
		consumerPort.process(context, message);
		assertEquals("Hell�", message.getVariable("result"));
	}

	@Test
	public void testEncodeResponse() throws Exception {
		String test = "<test>�</test>\n";
		ESBMessage message = new ESBMessage(BodyType.STRING, test);
		message.setSinkEncoding("utf-16");
		Action action = createAssignAction("request", ".");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(action);
		action = action.setNextAction(new TerminalAction() {});
		consumerPort.process(context, message);
		assertTrue(Arrays.equals(test.getBytes("utf-16"), message.getBodyAsByteArray(context)));
	}

	@Test
	public void testContentType() throws Exception {
		assertEquals("gzip", getValueFromHttpHeader("gzip,deflate"));
		assertEquals("utf-8", getValueFromHttpHeader("utf-8, iso-8859-1;q=0.5"));
		assertEquals("UTF-8", getValueFromHttpHeader("application/soap+xml;action=\"urn:listShipments\";charset=UTF-8", HTTP_HEADER_CONTENT_TYPE_PARAMETER_CHARSET));
		assertEquals("utf-8", getValueFromHttpHeader("text/xml; charset=\"utf-8\"", HTTP_HEADER_CONTENT_TYPE_PARAMETER_CHARSET));
		assertEquals(HTTP_HEADER_CONTENT_TYPE_SOAP11, parseContentType("text/xml; charset=\"utf-8\""));
		assertEquals("urn:listShipments", getValueFromHttpHeader("application/soap+xml;charset=UTF-8;action=\"urn:listShipments\"", HTTP_HEADER_CONTENT_TYPE_PARAMETER_ACTION));
	}

	@Test
	public void testPathEncoding() throws Exception {
		assertEquals("root%2Fbook%20name+m", URLUtils.encodePathSegment("root/book name+m"));
		assertEquals("root%2Fbook+name%2Bm", URLUtils.encode("root/book name+m"));
	}

	@Test
	public void testAccept() throws Exception {
		BigDecimal quality = getQuality("text/html, image/gif, image/jpeg, *; q=.2, */*; q=.2", "text/xml");
		assertTrue(quality.signum() > 0);
		quality = getQuality("gzip;q=1.0, identity; q=0.5, *;q=0.2, compress; q=.000", "compress");
		assertTrue(quality == null);
		quality = getQuality("gzip;q=1.0, identity; q=0.5, *;q=0.2, compress; q=.000", "brotli");
		assertTrue(quality != null);
		System.out.println(getBestQualityValue("text/html, image/gif, image/jpeg, *; q=.2, */*; q=.2"));
		System.out.println(getBestQualityValue("gzip;q=1.0, identity; q=0.5, *;q=0, compress"));
		System.out.println(getBestQualityValue("iso-8859-5; q=0.5, unicode-1-1"));
	}

	@Test
	public void testMalformedContentType() {
		// Before bugfix this resulted in an infinite loop
		determineCharset("application/json; UTF-8");
	}

	@Test
	public void testCreateURLEncodedString() {
		ESBMessage message = new ESBMessage(BodyType.INVALID, null);
		message.putVariable("k1", "Hello ");
		message.putVariable("k2", "World!");
		assertEquals("k1=Hello+&k2=World%21", message.createURLEncodedString("k1,k2"));
	}

	@Test
	public void testRepairXML() throws Exception {
		char[] chars = Character.toChars(26);
		char char1 = java.lang.reflect.Array.getChar(chars, 0);
		conv(char1);
		ESBMessage message = new ESBMessage(BodyType.STRING, "<root>\u001A</root>");
		TransformAction transformAction = new TransformAction("root/text()");
		//transformAction.setNextAction(new DumpAction());
		// in XML &#x1a; &
		SetMessageAction action = new SetMessageAction(getClass().getClassLoader(), StringWrapper.create("${body.replace(_string,'?')}"), null, null);
		action.addAssignment("_codePoint", false, "26", "java.lang.Integer", null, null);
		action.addAssignment("_chars", false, "${_codePoint}", "java.lang.Character", "toChars", null);
		action.addAssignment("_string", false, "${_chars}", "java.lang.String", "valueOf", null);
		action.setNextAction(transformAction);
		action.process(context, message);
		assertEquals("?", message.getBodyAsString(context));
	}

	@BeforeClass
	public static void init() {
		System.setProperty("esb0.useDefaultIdentityTransformer", "true");
	}

	@AfterClass
	public static void destroy() {
		System.setProperty("esb0.useDefaultIdentityTransformer", "false");
	}

	@Test
	public void testPrettyPrintXML() throws Exception {
		ESBMessage message = new ESBMessage(BodyType.STRING, "<root><child/></root>");
		Properties serializationParameters = new Properties();
		serializationParameters.put("{http://xml.apache.org/xslt}indent-amount", "1");
		// Not working with Saxon-HE
		//serializationParameters.setProperty("{http://saxon.sf.net/}indent-spaces", "1");
		message.putVariable(ESBConstants.serializationParameters, serializationParameters);
		Action action = new TransformAction(".");
		action.setNextAction(new SetMessageAction(getClass().getClassLoader(), StringWrapper.create("${body}"), null, null));
		action.process(context, message);
		String bodyAsString = message.getBodyAsString(context);
		// -Desb0.useDefaultIdentityTransformer=true
		//assertEquals("<root>" + System.lineSeparator() + " <child/>" + System.lineSeparator() + "</root>" + System.lineSeparator(), bodyAsString);
	}

	void conv(int i) {
		System.out.println((char) i);
	}
	
//	@Test
//	public void testWindows() {
//		File tempDir= new File("c:\\Software");
//		File cco = new File(tempDir, "cco");
//		assertTrue(cco.mkdir());
//		File Cco = new File(tempDir, "Cco");
//		boolean exists = false;
//		File[] listFiles = tempDir.listFiles();
//		for (File file : listFiles) {
//			if (file.getName().equals(Cco.getName())) {
//				exists = true;
//				break;
//			}
//		}
//		System.out.println(exists);
////		boolean mkdir = Cco.mkdir();
////		System.out.println(mkdir);
//		assertTrue(cco.delete());
//	}

	
}
