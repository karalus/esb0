package com.artofarc.esb.action;

import static org.junit.Assert.*;

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
	public void testEncodeResponse() throws Exception {
		String test = "<test>ä</test>\n";
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
	
}
