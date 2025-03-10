package com.artofarc.esb.action;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Assert;
import org.junit.Test;

import com.artofarc.esb.AbstractESBTest;
import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.artifact.Directory;
import com.artofarc.esb.artifact.XMLProcessingArtifact;
import com.artofarc.esb.artifact.XQueryArtifact;
import com.artofarc.esb.artifact.XSDArtifact;
import com.artofarc.esb.artifact.XSLTArtifact;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.TimeGauge;
import com.artofarc.util.XMLProcessorFactory;
import com.artofarc.util.XQuerySource;

public class TransformerTest extends AbstractESBTest {

	@Test
	public void testTransform() throws Exception {
		XSLTArtifact xsltArtifact = new XSLTArtifact(getGlobalContext().getFileSystem(), getGlobalContext().getFileSystem().getRoot(), "transformation.xslt");
		xsltArtifact.setContent(readFile("src/test/resources/transformation.xslt"));
		xsltArtifact.validateInternal(getGlobalContext());
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.setContentType("text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(createUnwrapSOAPAction(false, true), new XSLTAction(xsltArtifact.getURI(), Collections.<String> emptyList()), new TransformAction(
				"."), new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testTransformWithParam() throws Exception {
		XSLTArtifact xsltArtifact = new XSLTArtifact(getGlobalContext().getFileSystem(), getGlobalContext().getFileSystem().getRoot(), "transformation.xslt");
		xsltArtifact.setContent(readFile("src/test/resources/transformation.xslt"));
		xsltArtifact.validateInternal(getGlobalContext());
		byte[] file = readFile("src/test/resources/SOAPRequest.xml");
		int count = 1;//1000000;
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(
				createUnwrapSOAPAction(false, true),
				new XSLTAction(xsltArtifact.getURI(), xsltArtifact.getParams()),
				new TransformAction("."),
				count > 1 ?	new TerminalAction() {} : new DumpAction()
				);
		TimeGauge timeGauge = new TimeGauge(Action.logger, 0L, false);
		timeGauge.startTimeMeasurement();
		for (int i = 0; i < count; ++i) {
			ESBMessage message = new ESBMessage(BodyType.BYTES, file);
			message.setContentType("text/xml");
			consumerPort.process(context, message);
		}
		long measurement = timeGauge.stopTimeMeasurement("Performance", false);
		System.out.println("Average in µs: " + measurement * 1000 / count);
		
	}

	@Test
	public void testTransformStart() throws Exception {
		XSLTArtifact xsltArtifact = new XSLTArtifact(getGlobalContext().getFileSystem(), getGlobalContext().getFileSystem().getRoot(), "transformation.xslt");
		xsltArtifact.setContent(readFile("src/test/resources/transformation.xslt"));
		xsltArtifact.validateInternal(getGlobalContext());
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(new XSLTAction(xsltArtifact.getURI(), Collections.<String> emptyList()), new TransformAction("."), new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testTransformEnd() throws Exception {
		XSLTArtifact xsltArtifact = new XSLTArtifact(getGlobalContext().getFileSystem(), getGlobalContext().getFileSystem().getRoot(), "transformation.xslt");
		xsltArtifact.setContent(readFile("src/test/resources/transformation.xslt"));
		xsltArtifact.validateInternal(getGlobalContext());
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(new XSLTAction(xsltArtifact.getURI(), Collections.<String> emptyList()), new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testTransformDouble() throws Exception {
		XSLTArtifact xsltArtifact = new XSLTArtifact(getGlobalContext().getFileSystem(), getGlobalContext().getFileSystem().getRoot(), "transformation.xslt");
		xsltArtifact.setContent(readFile("src/test/resources/transformation.xslt"));
		xsltArtifact.validateInternal(getGlobalContext());
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(new XSLTAction(xsltArtifact.getURI(), Collections.<String> emptyList()), new XSLTAction(
				xsltArtifact.getURI(), Collections.<String> emptyList()), new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testValidate() throws Exception {
		XSDArtifact xsdArtifact = new XSDArtifact(null, null, "kdf");
		xsdArtifact.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
		xsdArtifact.validateInternal(getGlobalContext());
		XSLTArtifact xsltArtifact = new XSLTArtifact(getGlobalContext().getFileSystem(), getGlobalContext().getFileSystem().getRoot(), "transformation.xslt");
		xsltArtifact.setContent(readFile("src/test/resources/transformation.xslt"));
		xsltArtifact.validateInternal(getGlobalContext());
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.setContentType("text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(createUnwrapSOAPAction(false, true), new TransformAction(
				"declare namespace v1=\"http://aoa.de/ei/foundation/v1\"; v1:messageHeader"), new XSLTAction(xsltArtifact.getURI(), Collections.<String> emptyList()),
				createValidateAction(xsdArtifact), new DumpAction());
		message.putVariable("param1", 42);
		consumerPort.process(context, message);
	}

	@Test
	public void testStreamingValidate() throws Exception {
		XSDArtifact xsdArtifact = new XSDArtifact(null, null, "kdf");
		xsdArtifact.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
		xsdArtifact.validateInternal(getGlobalContext());
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.setContentType("text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(createUnwrapSOAPAction(false, true), new TransformAction(
				"declare namespace v1=\"http://aoa.de/ei/foundation/v1\"; v1:messageHeader"), new SAXValidationAction(xsdArtifact.getSchema()),
				new TransformAction("."), new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testValidatePerformance() throws Exception {
		// Set to 1000000 for real test
		validatePerformance(1, false);
		validatePerformance(1, true);
	}
	
	private void validatePerformance(int count, boolean useSAXValidation) throws Exception {
		XSDArtifact xsdArtifact = new XSDArtifact(null, null, "kdf");
		xsdArtifact.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
		xsdArtifact.validateInternal(null);
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(createUnwrapSOAPAction(false, true),
				new TransformAction("declare namespace v1=\"http://aoa.de/ei/foundation/v1\"; v1:messageHeader"),
				useSAXValidation ? new SAXValidationAction(xsdArtifact.getSchema()) : createValidateAction(xsdArtifact), new TerminalAction() {});
		byte[] file = readFile("src/test/resources/SOAPRequest.xml");
		TimeGauge timeGauge = new TimeGauge(Action.logger);
		timeGauge.startTimeMeasurement();
		for (int i = 0; i < count; ++i) {
			ESBMessage message = new ESBMessage(BodyType.BYTES, file);
			message.setContentType("text/xml");
			consumerPort.process(context, message);
		}
		long measurement = timeGauge.stopTimeMeasurement("Performance", false);
		System.out.println("Average in µs: " + measurement * 1000 / count);
	}

	@Test
	public void testStreamingValidateEnd() throws Exception {
		XSDArtifact xsdArtifact = new XSDArtifact(null, null, "kdf");
		xsdArtifact.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
		xsdArtifact.validateInternal(getGlobalContext());
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.setContentType("text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(createUnwrapSOAPAction(false, true), new TransformAction(
				"declare namespace v1=\"http://aoa.de/ei/foundation/v1\"; v1:messageHeader"), new DumpAction(), new SAXValidationAction(xsdArtifact.getSchema()),
				new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testXSLTWithStaticData() throws Exception {
		XSDArtifact xsdArtifact = new XSDArtifact(null, null, "kdf");
		xsdArtifact.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
		xsdArtifact.validateInternal(getGlobalContext());
		Directory root = getGlobalContext().getFileSystem().getRoot();
		Directory stylesheetes = new Directory(getGlobalContext().getFileSystem(), root, "stylesheetes");
		Directory staticData = new Directory(getGlobalContext().getFileSystem(), root, "data");
		XSLTArtifact xsltArtifact1 = new XSLTArtifact(getGlobalContext().getFileSystem(), stylesheetes, "transformationUsingStaticData.xslt");
		xsltArtifact1.setContent(readFile("src/test/resources/transformationUsingStaticData.xslt"));
		XSLTArtifact xsltArtifact = new XSLTArtifact(getGlobalContext().getFileSystem(), stylesheetes, "transformation.xslt");
		xsltArtifact.setContent(readFile("src/test/resources/transformation.xslt"));
		XMLProcessingArtifact staticXML = new XMLProcessingArtifact(getGlobalContext().getFileSystem(), staticData, "static.xml");
		staticXML.setContent("<root>Hello World!</root>".getBytes());
		xsltArtifact1.validateInternal(getGlobalContext());

		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.setContentType("text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(createUnwrapSOAPAction(false, true), new TransformAction(
				"declare namespace v1=\"http://aoa.de/ei/foundation/v1\"; v1:messageHeader"), new XSLTAction(xsltArtifact1.getURI(), Collections.<String> emptyList()),
				new DumpAction());
		message.putVariable("param1", 42);
		consumerPort.process(context, message);
	}

	@Test
	public void testTransformXQuery() throws Exception {
		XQueryArtifact xQueryArtifact = new XQueryArtifact(getGlobalContext().getFileSystem(), getGlobalContext().getFileSystem().getRoot(), "transform1.xqy");
		xQueryArtifact.setContent(readFile("src/test/resources/transform1.xqy"));
		xQueryArtifact.validateInternal(getGlobalContext());
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.setContentType("text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(createUnwrapSOAPAction(false, true), createTransformAction(xQueryArtifact), new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testTransformXQueryWithParam() throws Exception {
		XQueryArtifact xQueryArtifact = new XQueryArtifact(getGlobalContext().getFileSystem(), getGlobalContext().getFileSystem().getRoot(), "transform2.xqy");
		xQueryArtifact.setContent(readFile("src/test/resources/transform2.xqy"));
		xQueryArtifact.validateInternal(getGlobalContext());
		ESBMessage message = new ESBMessage(BodyType.STRING, "");
		message.putVariable("id", Arrays.asList("23", "37"));
		message.putVariable("opt", "optional");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(createTransformAction(xQueryArtifact), new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testTransformXQueryWithParamNull() throws Exception {
		XQueryArtifact xQueryArtifact = new XQueryArtifact(getGlobalContext().getFileSystem(), getGlobalContext().getFileSystem().getRoot(), "transform2.xqy");
		xQueryArtifact.setContent(readFile("src/test/resources/transform2.xqy"));
		xQueryArtifact.validateInternal(getGlobalContext());
		ESBMessage message = new ESBMessage(BodyType.STRING, "");
//		message.putVariable("id", Arrays.asList("23", "37"));
//		message.putVariable("opt", "optional");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(createTransformAction(xQueryArtifact), new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testTransformRegEx() throws Exception {
		ESBMessage message = new ESBMessage(BodyType.STRING, "<nr>8001</nr>");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(new TransformAction("declare function local:substring-after-match( $arg as xs:string?, $regex as xs:string) as xs:string? {replace($arg,concat('^.*?',$regex),'')}; local:substring-after-match(*/text(), '[0]+')"), new DumpAction());
		consumerPort.process(context, message);
		Assert.assertEquals("1", message.getBodyAsString(context));
	}

	@Test
	public void testTransformException() throws Exception {
		Exception exc = new Exception(new Exception("inner"));
		ESBMessage message = new ESBMessage(BodyType.INVALID, null);
		message.putVariable("exception", exc);
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(new TransformAction("declare namespace soapenv='http://schemas.xmlsoap.org/soap/envelope/'; declare variable $exception as document-node() external; "
				+ "<soapenv:Body><soapenv:Fault><faultcode>soapenv:Server</faultcode><faultstring>{$exception/exception/message/text()}</faultstring><detail>{$exception/exception/cause}</detail></soapenv:Fault></soapenv:Body>"), new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testTransformElement() throws Exception {
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.setContentType("text/xml");
		XQuerySource xquery = XQuerySource.create("declare namespace v1=\"http://aoa.de/ei/foundation/v1\"; declare variable $messageHeader as element(v1:messageHeader) external; <neu>{$messageHeader}</neu>");
		// Use different factory for init as it also happens in real runtime
		javax.xml.xquery.XQConnection connection = XMLProcessorFactory.newInstance(null).getConnection();
		xquery.prepareExpression(connection, null);
		connection.close();
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(createUnwrapSOAPAction(false, true),
				createAssignAction("messageHeader", "*[1]"),
				new TransformAction(xquery, null, null, false, null),
				new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testReposition() throws Exception {
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.setContentType("text/xml; charset=\"utf-8\"");
		Action action = createUnwrapSOAPAction(false, true);
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(action);
		action = action.setNextAction(createAssignAction("pos1", "local-name(*[1])"));
		action = action.setNextAction(new XOPDeserializeAction());
		action = action.setNextAction(createAssignAction("pos2", "local-name(*[1])"));
		action = action.setNextAction(new DumpAction());
		consumerPort.process(context, message);
		assertEquals(message.<String>getVariable("pos1"), message.<String>getVariable("pos2"));
	}

	@Test
	public void testRepositionMaterializeMessage() throws Exception {
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.setContentType("text/xml; charset=\"utf-8\"");
		Action action = createUnwrapSOAPAction(false, true);
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(action);
		action = action.setNextAction(createAssignAction("pos1", "local-name(*[1])"));
		action = action.setNextAction(new XOPDeserializeAction());
		action = action.setNextAction(new ForkAction(null, true, false, false, new DumpAction()));
		action = action.setNextAction(createAssignAction("pos2", "local-name(*[1])"));
		action = action.setNextAction(new DumpAction());
		consumerPort.process(context, message);
		assertEquals(message.<String>getVariable("pos1"), message.<String>getVariable("pos2"));
	}

}
