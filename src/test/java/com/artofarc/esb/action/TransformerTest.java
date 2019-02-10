package com.artofarc.esb.action;

import static org.junit.Assert.*;

import org.junit.Test;

import com.artofarc.esb.AbstractESBTest;
import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.artifact.Directory;
import com.artofarc.esb.artifact.XMLArtifact;
import com.artofarc.esb.artifact.XSDArtifact;
import com.artofarc.esb.artifact.XSLTArtifact;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.TimeGauge;

public class TransformerTest extends AbstractESBTest {

	@Test
	public void testTransform() throws Exception {
		XSLTArtifact xsltArtifact = new XSLTArtifact(null, "transformation.xslt");
		xsltArtifact.setContent(readFile("src/test/resources/transformation.xslt"));
		xsltArtifact.validateInternal(context.getPoolContext().getGlobalContext());
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(new UnwrapSOAPAction(false, true), new XSLTAction(xsltArtifact.getTemplates()), new TransformAction(
				"."), new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testTransformStart() throws Exception {
		XSLTArtifact xsltArtifact = new XSLTArtifact(null, "transformation.xslt");
		xsltArtifact.setContent(readFile("src/test/resources/transformation.xslt"));
		xsltArtifact.validateInternal(context.getPoolContext().getGlobalContext());
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(new XSLTAction(xsltArtifact.getTemplates()), new TransformAction("."), new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testTransformEnd() throws Exception {
		XSLTArtifact xsltArtifact = new XSLTArtifact(null, "transformation.xslt");
		xsltArtifact.setContent(readFile("src/test/resources/transformation.xslt"));
		xsltArtifact.validateInternal(context.getPoolContext().getGlobalContext());
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(new XSLTAction(xsltArtifact.getTemplates()), new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testTransformDouble() throws Exception {
		XSLTArtifact xsltArtifact = new XSLTArtifact(null, "transformation.xslt");
		xsltArtifact.setContent(readFile("src/test/resources/transformation.xslt"));
		xsltArtifact.validateInternal(context.getPoolContext().getGlobalContext());
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(new XSLTAction(xsltArtifact.getTemplates()), new XSLTAction(
				xsltArtifact.getTemplates()), new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testValidate() throws Exception {
		XSDArtifact xsdArtifact = new XSDArtifact(null, "kdf");
		xsdArtifact.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
		xsdArtifact.validateInternal(null);
		XSLTArtifact xsltArtifact = new XSLTArtifact(null, "transformation.xslt");
		xsltArtifact.setContent(readFile("src/test/resources/transformation.xslt"));
		xsltArtifact.validateInternal(context.getPoolContext().getGlobalContext());
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(new UnwrapSOAPAction(false, true), new TransformAction(
				"declare namespace v1=\"http://aoa.de/ei/foundation/v1\"; v1:messageHeader"), new XSLTAction(xsltArtifact.getTemplates()),
				new ValidateAction(xsdArtifact.getSchema(), ".", null), new DumpAction());
		message.putVariable("param1", 42);
		consumerPort.process(context, message);
	}

	@Test
	public void testStreamingValidate() throws Exception {
		XSDArtifact xsdArtifact = new XSDArtifact(null, "kdf");
		xsdArtifact.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
		xsdArtifact.validateInternal(null);
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(new UnwrapSOAPAction(false, true), new TransformAction(
				"declare namespace v1=\"http://aoa.de/ei/foundation/v1\"; v1:messageHeader"), new SAXValidationAction(xsdArtifact.getSchema()),
				new TransformAction("."), new DumpAction());
		consumerPort.process(context, message);
	}

	public void testStreamingValidatePerformance() throws Exception {
		XSDArtifact xsdArtifact = new XSDArtifact(null, "kdf");
		xsdArtifact.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
		xsdArtifact.validateInternal(null);
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(new UnwrapSOAPAction(false, true), new TransformAction(
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
		XSDArtifact xsdArtifact = new XSDArtifact(null, "kdf");
		xsdArtifact.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
		xsdArtifact.validateInternal(null);
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(new UnwrapSOAPAction(false, true), new TransformAction(
				"declare namespace v1=\"http://aoa.de/ei/foundation/v1\"; v1:messageHeader"), new ValidateAction(xsdArtifact.getSchema(), ".", null),
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
		XSDArtifact xsdArtifact = new XSDArtifact(null, "kdf");
		xsdArtifact.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
		xsdArtifact.validateInternal(null);
		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(new UnwrapSOAPAction(false, true), new TransformAction(
				"declare namespace v1=\"http://aoa.de/ei/foundation/v1\"; v1:messageHeader"), new SAXValidationAction(xsdArtifact.getSchema()),
				new DumpAction());
		consumerPort.process(context, message);
	}

	@Test
	public void testXSLTWithStaticData() throws Exception {
		XSDArtifact xsdArtifact = new XSDArtifact(null, "kdf");
		xsdArtifact.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
		xsdArtifact.validateInternal(null);
		Directory root = context.getPoolContext().getGlobalContext().getFileSystem().getRoot();
		Directory stylesheetes = new Directory(root, "stylesheetes");
		Directory staticData = new Directory(root, "data");
		XSLTArtifact xsltArtifact1 = new XSLTArtifact(stylesheetes, "transformationUsingStaticData.xslt");
		xsltArtifact1.setContent(readFile("src/test/resources/transformationUsingStaticData.xslt"));
		XSLTArtifact xsltArtifact = new XSLTArtifact(stylesheetes, "transformation.xslt");
		xsltArtifact.setContent(readFile("src/test/resources/transformation.xslt"));
		XMLArtifact staticXML = new XMLArtifact(staticData, "static.xml");
		staticXML.setContent("<root>Hello World!</root>".getBytes());
		xsltArtifact1.validateInternal(context.getPoolContext().getGlobalContext());

		ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
		message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml");
		ConsumerPort consumerPort = new ConsumerPort(null);
		consumerPort.setStartAction(new UnwrapSOAPAction(false, true), new TransformAction(
				"declare namespace v1=\"http://aoa.de/ei/foundation/v1\"; v1:messageHeader"), new XSLTAction(xsltArtifact1.getTemplates()),
				new DumpAction());
		message.putVariable("param1", 42);
		consumerPort.process(context, message);
	}

}
