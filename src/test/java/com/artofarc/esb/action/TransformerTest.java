package com.artofarc.esb.action;

import org.junit.Assert;
import org.junit.Test;

import com.artofarc.esb.AbstractESBTest;
import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.artifact.Directory;
import com.artofarc.esb.artifact.XMLArtifact;
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
		message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(createUnwrapSOAPAction(false, true), new XSLTAction(xsltArtifact.getTemplates()), new TransformAction(
				"."), new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testTransformStart() throws Exception {
		XSLTArtifact xsltArtifact = new XSLTArtifact(null, null, "transformation.xslt");
		xsltArtifact.setContent(readFile("src/test/resources/transformation.xslt"));
		xsltArtifact.validateInternal(getGlobalContext());
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(new XSLTAction(xsltArtifact.getTemplates()), new TransformAction("."), new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testTransformEnd() throws Exception {
		XSLTArtifact xsltArtifact = new XSLTArtifact(null, null, "transformation.xslt");
		xsltArtifact.setContent(readFile("src/test/resources/transformation.xslt"));
		xsltArtifact.validateInternal(getGlobalContext());
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(new XSLTAction(xsltArtifact.getTemplates()), new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testTransformDouble() throws Exception {
		XSLTArtifact xsltArtifact = new XSLTArtifact(null, null, "transformation.xslt");
		xsltArtifact.setContent(readFile("src/test/resources/transformation.xslt"));
		xsltArtifact.validateInternal(getGlobalContext());
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(new XSLTAction(xsltArtifact.getTemplates()), new XSLTAction(
				xsltArtifact.getTemplates()), new DumpAction());
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
		message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(createUnwrapSOAPAction(false, true), new TransformAction(
				"declare namespace v1=\"http://aoa.de/ei/foundation/v1\"; v1:messageHeader"), new XSLTAction(xsltArtifact.getTemplates()),
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
		message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(createUnwrapSOAPAction(false, true), new TransformAction(
				"declare namespace v1=\"http://aoa.de/ei/foundation/v1\"; v1:messageHeader"), new SAXValidationAction(xsdArtifact.getSchema()),
				new TransformAction("."), new DumpAction());
		consumerPort.process(context, message);
	}

	public void testStreamingValidatePerformance() throws Exception {
		XSDArtifact xsdArtifact = new XSDArtifact(null, null, "kdf");
		xsdArtifact.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
		xsdArtifact.validateInternal(getGlobalContext());
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(createUnwrapSOAPAction(false, true), new TransformAction(
				"declare namespace v1=\"http://aoa.de/ei/foundation/v1\"; v1:messageHeader"), new SAXValidationAction(xsdArtifact.getSchema()),
				new TerminalAction(){});
		byte[] file = readFile("src/test/resources/SOAPRequest.xml");
		TimeGauge timeGauge = new TimeGauge(Action.logger);
		timeGauge.startTimeMeasurement();
		for (int i = 0; i < 1000000; ++i) {
			ESBMessage message = new ESBMessage(BodyType.BYTES, file);
			message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
			consumerPort.process(context, message);
		}
		timeGauge.stopTimeMeasurement("Performance", false);
	}

	public void testValidatePerformance() throws Exception {
		XSDArtifact xsdArtifact = new XSDArtifact(null, null, "kdf");
		xsdArtifact.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
		xsdArtifact.validateInternal(null);
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(createUnwrapSOAPAction(false, true), new TransformAction(
				"declare namespace v1=\"http://aoa.de/ei/foundation/v1\"; v1:messageHeader"), createValidateAction(xsdArtifact),
				new TerminalAction() {});
		byte[] file = readFile("src/test/resources/SOAPRequest.xml");
		TimeGauge timeGauge = new TimeGauge(Action.logger);
		timeGauge.startTimeMeasurement();
		for (int i = 0; i < 1000000; ++i) {
			ESBMessage message = new ESBMessage(BodyType.BYTES, file);
			message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
			consumerPort.process(context, message);
		}
		timeGauge.stopTimeMeasurement("Performance", false);
	}

	@Test
	public void testStreamingValidateEnd() throws Exception {
		XSDArtifact xsdArtifact = new XSDArtifact(null, null, "kdf");
		xsdArtifact.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
		xsdArtifact.validateInternal(getGlobalContext());
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(createUnwrapSOAPAction(false, true), new TransformAction(
				"declare namespace v1=\"http://aoa.de/ei/foundation/v1\"; v1:messageHeader"), new SAXValidationAction(xsdArtifact.getSchema()),
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
		XMLArtifact staticXML = new XMLArtifact(getGlobalContext().getFileSystem(), staticData, "static.xml");
		staticXML.setContent("<root>Hello World!</root>".getBytes());
		xsltArtifact1.validateInternal(getGlobalContext());

		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(createUnwrapSOAPAction(false, true), new TransformAction(
				"declare namespace v1=\"http://aoa.de/ei/foundation/v1\"; v1:messageHeader"), new XSLTAction(xsltArtifact1.getTemplates()),
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
		message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(createUnwrapSOAPAction(false, true), new TransformAction(XQuerySource.create(xQueryArtifact.getContent()), null), new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testTransformRegEx() throws Exception {
		ESBMessage message = new ESBMessage(BodyType.STRING, "<nr>8001</nr>");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(new TransformAction(XQuerySource.create("declare function local:substring-after-match( $arg as xs:string?, $regex as xs:string) as xs:string? {replace($arg,concat('^.*?',$regex),'')}; local:substring-after-match(*/text(), '[0]+')"), null), new DumpAction());
		consumerPort.process(context, message);
		Assert.assertEquals("1", message.getBodyAsString(context));
	}

}
