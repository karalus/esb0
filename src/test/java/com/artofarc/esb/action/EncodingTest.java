package com.artofarc.esb.action;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.util.Arrays;

import org.junit.Test;

import com.artofarc.esb.AbstractESBTest;
import com.artofarc.esb.ConsumerPort;
import static com.artofarc.esb.http.HttpConstants.*;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
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
		assertEquals(SOAP_1_1_CONTENT_TYPE, parseContentType("text/xml; charset=\"utf-8\""));
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

}
