package com.artofarc.esb.action;

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
import com.artofarc.esb.context.XQuerySource;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.TimeGauge;

public class TransformerTest extends AbstractESBTest {

	@Test
	public void testTransform() throws Exception {
		XSLTArtifact xsltArtifact = new XSLTArtifact(null, null, "transformation.xslt");
		xsltArtifact.setContent(readFile("src/test/resources/transformation.xslt"));
		xsltArtifact.validateInternal(getGlobalContext());
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(createUnwrapSOAPAction(false, true), new XSLTAction(xsltArtifact.getTemplates(), Collections.<String> emptyList()), new TransformAction(
				"."), new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testTransformStart() throws Exception {
		XSLTArtifact xsltArtifact = new XSLTArtifact(null, null, "transformation.xslt");
		xsltArtifact.setContent(readFile("src/test/resources/transformation.xslt"));
		xsltArtifact.validateInternal(getGlobalContext());
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(new XSLTAction(xsltArtifact.getTemplates(), Collections.<String> emptyList()), new TransformAction("."), new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testTransformEnd() throws Exception {
		XSLTArtifact xsltArtifact = new XSLTArtifact(null, null, "transformation.xslt");
		xsltArtifact.setContent(readFile("src/test/resources/transformation.xslt"));
		xsltArtifact.validateInternal(getGlobalContext());
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(new XSLTAction(xsltArtifact.getTemplates(), Collections.<String> emptyList()), new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testTransformDouble() throws Exception {
		XSLTArtifact xsltArtifact = new XSLTArtifact(null, null, "transformation.xslt");
		xsltArtifact.setContent(readFile("src/test/resources/transformation.xslt"));
		xsltArtifact.validateInternal(getGlobalContext());
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(new XSLTAction(xsltArtifact.getTemplates(), Collections.<String> emptyList()), new XSLTAction(
				xsltArtifact.getTemplates(), Collections.<String> emptyList()), new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testValidate() throws Exception {
		XSDArtifact xsdArtifact = new XSDArtifact(null, null, "kdf");
		xsdArtifact.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
		xsdArtifact.validateInternal(getGlobalContext());
		XSLTArtifact xsltArtifact = new XSLTArtifact(null, null, "transformation.xslt");
		xsltArtifact.setContent(readFile("src/test/resources/transformation.xslt"));
		xsltArtifact.validateInternal(getGlobalContext());
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(createUnwrapSOAPAction(false, true), new TransformAction(
				"declare namespace v1=\"http://aoa.de/ei/foundation/v1\"; v1:messageHeader"), new XSLTAction(xsltArtifact.getTemplates(), Collections.<String> emptyList()),
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
		message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
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
			message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
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
		message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
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
		message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(createUnwrapSOAPAction(false, true), new TransformAction(
				"declare namespace v1=\"http://aoa.de/ei/foundation/v1\"; v1:messageHeader"), new XSLTAction(xsltArtifact1.getTemplates(), Collections.<String> emptyList()),
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
		message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(createUnwrapSOAPAction(false, true), createTransformAction(xQueryArtifact), new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testTransformRegEx() throws Exception {
		ESBMessage message = new ESBMessage(BodyType.STRING, "<nr>8001</nr>");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(new TransformAction(XQuerySource.create("declare function local:substring-after-match( $arg as xs:string?, $regex as xs:string) as xs:string? {replace($arg,concat('^.*?',$regex),'')}; local:substring-after-match(*/text(), '[0]+')"), null, null), new DumpAction());
		consumerPort.process(context, message);
		Assert.assertEquals("1", message.getBodyAsString(context));
	}

}
