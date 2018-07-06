package com.artofarc.esb.action;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import javax.xml.bind.JAXBContext;

import org.junit.Before;
import org.junit.Test;

import com.artofarc.esb.AbstractESBTest;
import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.artifact.FileSystem;
import com.artofarc.esb.artifact.XSDArtifact;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.PoolContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;

public class JsonXmlTest extends AbstractESBTest {

	private JAXBContext jaxbContext;
	Map<String, String> urisToPrefixes = new HashMap<>();
	private FileSystem fileSystem;

	@Before
	public void createContext() throws Exception {
		context = new Context(new PoolContext(new GlobalContext()));
      fileSystem = new FileSystem();
      fileSystem.parseDirectory(context.getPoolContext().getGlobalContext(), new File("src/test/resources/example/"));
      XSDArtifact xsd = fileSystem.getArtifact("de.aoa.ei.foundation.v1.xsd");
      jaxbContext = xsd.getJAXBContext();
      urisToPrefixes.put("http://aoa.de/ei/foundation/v1", "");
	}

	@Test
	public void testXML2JsonInPipeline() throws Exception {
		ESBMessage message = new ESBMessage(BodyType.BYTES, SOAPTest.readFile("src/test/resources/SOAPRequest.xml"));
		message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
		Action action = new UnwrapSOAPAction(false, false);
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(action);
		action = action.setNextAction(new TransformAction("declare namespace v1=\"http://aoa.de/ei/foundation/v1\"; (*/v1:messageHeader)"));
		action = action.setNextAction(new XML2JsonAction(jaxbContext, new HashMap<String, String>(), null, true));
		action = action.setNextAction(new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testXML2Json() throws Exception {
		ESBMessage message = new ESBMessage(BodyType.BYTES, SOAPTest.readFile("src/test/resources/MessageHeader.xml"));
		message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
		Action action = new XML2JsonAction(jaxbContext, new HashMap<String, String>(), null, true);
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(action);
		action = action.setNextAction(new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testJson2XML() throws Exception {
		ESBMessage message = new ESBMessage(BodyType.STRING, "{\"messageHeader\":{\"senderFQN\":\"usingPort1\",\"messageId\":\"M-bc5fd683-334f-4709-8c15-943c32baea89\",\"processInstanceId\":\"P-96181ac5-41f4-4ce5-bc95-111fe253c11d\"}}");
		//message.getHeaders().put(HttpOutboundAction.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
		Action action = new Json2XMLAction(jaxbContext, urisToPrefixes, null, true);
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(action);
		action = action.setNextAction(new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testXML2Json2XML() throws Exception {
		ESBMessage message = new ESBMessage(BodyType.BYTES, SOAPTest.readFile("src/test/resources/MessageHeader.xml"));
		message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
		Action action = new XML2JsonAction(jaxbContext, new HashMap<String, String>(), null, true);
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(action);
		action = action.setNextAction(new Json2XMLAction(jaxbContext, urisToPrefixes, null, true));
		action = action.setNextAction(new DumpAction());
		consumerPort.process(context, message);
	}
	
	@Test
	public void testXML2Json_1() throws Exception {
      XSDArtifact xsd = fileSystem.getArtifact("de.aoa.xsd.demo.v1.xsd");
		ESBMessage message = new ESBMessage(BodyType.BYTES, SOAPTest.readFile("src/test/resources/RequestBody.xml"));
		message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
		HashMap<String, String> urisToPrefixes = new HashMap<String, String>();
		urisToPrefixes.put("http://aoa.de/xsd/demo/v1/", "");
		urisToPrefixes.put("http://aoa.de/ei/foundation/v1", "ei1");
		Action action = new XML2JsonAction(xsd.getJAXBContext(), urisToPrefixes, null, true);
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(action);
		action = action.setNextAction(new DumpAction());
		consumerPort.process(context, message);
	}
	
	@Test
	public void testJson2XML_1() throws Exception {
      XSDArtifact xsd = fileSystem.getArtifact("de.aoa.xsd.demo.v1.xsd");
		ESBMessage message = new ESBMessage(BodyType.BYTES, SOAPTest.readFile("src/test/resources/RESTRequest.json"));
		message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "application/json; charset=\"utf-8\"");
		urisToPrefixes.clear();
		urisToPrefixes.put("http://aoa.de/xsd/demo/v1/", "");
		urisToPrefixes.put("http://aoa.de/ei/foundation/v1", "ei1");
		Action action = new Json2XMLAction(xsd.getJAXBContext(), urisToPrefixes, null, true);
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(action);
		action = action.setNextAction(new DumpAction());
		consumerPort.process(context, message);
	}
	
}
