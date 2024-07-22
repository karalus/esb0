package com.artofarc.esb.action;

import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.artofarc.esb.AbstractESBTest;
import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.artifact.FileSystem;
import com.artofarc.esb.artifact.XSDArtifact;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.sun.xml.xsom.XSSchemaSet;

public class JsonXmlTest extends AbstractESBTest {

	private XSSchemaSet schemaSet;
	Map<String, String> prefixesToUris = new HashMap<>();
	private FileSystem fileSystem;

	@Before
	public void createContext() throws Exception {
		createContext("src/test/resources/example/");
      fileSystem = getGlobalContext().getFileSystem();
      fileSystem.init(getGlobalContext());
      XSDArtifact xsd = fileSystem.getArtifact("de.aoa.ei.foundation.v1.xsd");
      schemaSet = xsd.getXSSchemaSet();
      prefixesToUris.put("", "http://aoa.de/ei/foundation/v1");
	}

	@Test
	public void testXML2JsonInPipeline() throws Exception {
		ESBMessage message = new ESBMessage(BodyType.BYTES, SOAPTest.readFile("src/test/resources/SOAPRequest.xml"));
		message.setContentType("text/xml; charset=\"utf-8\"");
		Action action = createUnwrapSOAPAction(false, false);
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(action);
		action = action.setNextAction(new TransformAction("declare namespace v1=\"http://aoa.de/ei/foundation/v1\"; (*/v1:messageHeader)"));
//	    XSDArtifact xsd = fileSystem.getArtifact("de.aoa.ei.foundation.v1.xsd");
//	    xsd.validate(getGlobalContext());
//		action = action.setNextAction(new SAXValidationAction(xsd.getSchema()));
		action = action.setNextAction(new XML2JsonAction(schemaSet, null, true, null, new HashMap<String, String>()));
		action = action.setNextAction(new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testXML2Json() throws Exception {
		ESBMessage message = new ESBMessage(BodyType.BYTES, SOAPTest.readFile("src/test/resources/MessageHeader.xml"));
		message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
		XML2JsonAction action = new XML2JsonAction(schemaSet, null, true, null, new HashMap<String, String>());
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(action);
		action.setNextAction(new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testJson2XML() throws Exception {
		ESBMessage message = new ESBMessage(BodyType.STRING, "{\"messageHeader\":{\"senderFQN\":\"usingPort1\",\"messageId\":\"M-bc5fd683-334f-4709-8c15-943c32baea89\",\"processInstanceId\":\"P-96181ac5-41f4-4ce5-bc95-111fe253c11d\"}}");
		//message.putHeader(HttpOutboundAction.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
		Action action = new Json2XMLAction(schemaSet, null, true, null, prefixesToUris, null);
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(action);
		action = action.setNextAction(new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testXML2Json2XML() throws Exception {
		ESBMessage message = new ESBMessage(BodyType.BYTES, SOAPTest.readFile("src/test/resources/MessageHeader.xml"));
		message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
		Action action = new XML2JsonAction(schemaSet, null, true, null, new HashMap<String, String>());
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(action);
		prefixesToUris.put("ns0", "http://aoa.de/ei/foundation/v1");
//		action = action.setNextAction(new DumpAction());
		action = action.setNextAction(new Json2XMLAction(schemaSet, null, true, null, prefixesToUris, null));
//		action = action.setNextAction(new XML2JsonAction(schemaSet, null, true, new HashMap<String, String>(), null));
		action = action.setNextAction(new DumpAction());
		consumerPort.process(context, message);
	}
	
	@Test
	public void testXML2Json_1() throws Exception {
      XSDArtifact xsd = fileSystem.getArtifact("de.aoa.xsd.demo.v1.xsd");
		ESBMessage message = new ESBMessage(BodyType.BYTES, SOAPTest.readFile("src/test/resources/RequestBody.xml"));
		message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
		Action action = new XML2JsonAction(xsd.getXSSchemaSet(), null, false, null, null);
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(action);
		action = action.setNextAction(new DumpAction());
		consumerPort.process(context, message);
	}
	
	@Test
	public void testJson2XML_1() throws Exception {
      XSDArtifact xsd = fileSystem.getArtifact("de.aoa.xsd.demo.v1.xsd");
		byte[] file = SOAPTest.readFile("src/test/resources/RESTRequest.json");
		ESBMessage message = new ESBMessage(BodyType.BYTES, file);
		message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "application/json; charset=\"utf-8\"");
		prefixesToUris.clear();
		prefixesToUris.put("", "http://aoa.de/xsd/demo/v1/");
		prefixesToUris.put("ei1", "http://aoa.de/ei/foundation/v1");
		// demoElementRequest xmlns="http://aoa.de/ei/foundation/v1"
		Json2XMLAction action = new Json2XMLAction(xsd.getXSSchemaSet(), "{http://aoa.de/xsd/demo/v1/}demoType", true, "{http://aoa.de/xsd/demo/v1/}demoElementRequest", prefixesToUris, null);
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(action);
		action.setNextAction(new DumpAction());
		consumerPort.process(context, message);
//		TimeGauge timeGauge = new TimeGauge(Action.logger, 0L, false);
//		timeGauge.startTimeMeasurement();
//		int count = 100000;
//		for (int i = 0; i < count; ++i) {
//			message = new ESBMessage(BodyType.BYTES, file);
//			message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "application/json; charset=\"utf-8\"");
//			consumerPort.process(context, message);
//		}
//		long measurement = timeGauge.stopTimeMeasurement("Performance", false);
//		System.out.println("Average in µs: " + measurement * 1000 / count);

	}
	
//	@Test
//	public void testJsonValue2XML() throws Exception {
//		XSDArtifact xsd = fileSystem.getArtifact("de.aoa.xsd.demo.v1.xsd");
//		ESBMessage message;
//		try (JsonReader jsonReader = JsonFactoryHelper.JSON_READER_FACTORY.createReader(new FileInputStream("src/test/resources/RESTRequest.json"))) {
//			message = new ESBMessage(BodyType.JSON_VALUE, jsonReader.readObject());
//		}
//		message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "application/json; charset=\"utf-8\"");
//		prefixesToUris.clear();
//		prefixesToUris.put("http://aoa.de/xsd/demo/v1/", "");
//		prefixesToUris.put("http://aoa.de/ei/foundation/v1", "ei1");
//		prefixesToUris = Collections.inverseMap(prefixesToUris.entrySet(), true);
//		Json2XMLAction action = new Json2XMLAction(xsd.getXSSchemaSet(), "{http://aoa.de/xsd/demo/v1/}demoType", true, "{http://aoa.de/xsd/demo/v1/}demoElementRequest", prefixesToUris, null);
//		ConsumerPort consumerPort = new ConsumerPort(null);
//		consumerPort.setStartAction(action);
//		action.setNextAction(new DumpAction());
//		consumerPort.process(context, message);
//	}

	@Test
	public void testJsonPrimitive2XML() throws Exception {
		ESBMessage message = new ESBMessage(BodyType.STRING, "true");
		message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "application/json; charset=\"utf-8\"");
		Json2XMLAction action = new Json2XMLAction(null, null, false, null, null, true);
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(action);
		action.setNextAction(new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testSOAPFault2Json() throws Exception {
		XSDArtifact xsd = fileSystem.getArtifact("/xmlcatalog/soap11.xsd");
		ESBMessage message = new ESBMessage(BodyType.BYTES, SOAPTest.readFile("src/test/resources/SOAP_Fault1.xml"));
		XML2JsonAction action = new XML2JsonAction(xsd.getXSSchemaSet(), "{http://schemas.xmlsoap.org/soap/envelope/}detail", false, null, null);
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(action);
		action.setNextAction(new DumpAction());
		consumerPort.process(context, message);
	}

}
