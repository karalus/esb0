package com.artofarc.esb.action;

import static org.junit.Assert.*;

import java.util.Arrays;

import org.junit.Test;

import com.artofarc.esb.AbstractESBTest;
import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;


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

}
