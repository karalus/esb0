package com.artofarc.esb.action;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.xml.sax.InputSource;

import com.artofarc.esb.AbstractESBTest;
import com.artofarc.esb.XmlSampleGenerator;
import com.artofarc.esb.artifact.XSDArtifact;
import com.artofarc.esb.json.Json2XmlParser;
import com.artofarc.esb.json.Json2XmlTransformer;
import com.artofarc.esb.json.Xml2JsonTransformer;
import com.artofarc.util.JAXPFactoryHelper;
import com.artofarc.util.StringWriter;
import com.artofarc.util.TimeGauge;
import com.sun.xml.xsom.*;
import com.sun.xml.xsom.parser.SchemaDocument;
import com.sun.xml.xsom.parser.XSOMParser;


public class XSOMTest extends AbstractESBTest {
	
	@BeforeClass
	public static void init() {
		System.setProperty("esb0.cacheXSGrammars", "true");
	}

	@AfterClass
	public static void destroy() {
		System.setProperty("esb0.cacheXSGrammars", "false");
	}

	
	@Test
	public void testXSOM() throws Exception {
		
		XSDArtifact xsdArtifact1 = new XSDArtifact(getGlobalContext().getFileSystem(), getGlobalContext().getFileSystem().getRoot(), "de.aoa.xsd.demo.v1.xsd");
		xsdArtifact1.setContent(readFile("src/test/resources/example/de.aoa.xsd.demo.v1.xsd"));
		XSDArtifact xsdArtifact2 = new XSDArtifact(getGlobalContext().getFileSystem(), getGlobalContext().getFileSystem().getRoot(), "de.aoa.ei.foundation.v1.xsd");
		xsdArtifact2.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));

		xsdArtifact1.validate(getGlobalContext());
		assertTrue(xsdArtifact2.isValidated());

		SAXParserFactory factory = SAXParserFactory.newInstance();
		factory.setNamespaceAware(true);
		XSOMParser xsomParser = new XSOMParser(factory);
		xsomParser.setEntityResolver(xsdArtifact1.getResolver());
		InputSource is = new InputSource(xsdArtifact1.getContentAsStream());
		is.setSystemId(xsdArtifact1.getURI());
		xsomParser.parse(is);
		Set<SchemaDocument> documents = xsomParser.getDocuments();
		assertEquals(3, documents.size());
//		for (SchemaDocument schemaDocument : documents) {
//			System.out.println(schemaDocument.getTargetNamespace());
//		}
		XSSchemaSet schemaSet = xsomParser.getResult();
		XSElementDecl messageHeader = schemaSet.getElementDecl(xsdArtifact2.getNamespace(), "messageHeader");
		XSComplexType type = messageHeader.getType().asComplexType();
		print(type, "");
	}
	
	private static void print(XSComplexType complexType, String indent) {
		printQName(complexType, indent + "ComplexType: ");
		XSParticle particle = complexType.getContentType().asParticle();
		if (particle != null) {
			XSTerm term = particle.getTerm();
			print(term.asModelGroup(), indent + "\t");
		} else {
			print(complexType.getContentType().asSimpleType(), indent + "\t");
		}
	}

	private static void print(XSModelGroup modelGroup, String indent) {
		System.out.println(indent + modelGroup.getCompositor());
		indent += "\t";
		String indent2 = indent + "\t";				
		for (XSParticle xsParticle : modelGroup.getChildren()) {
			XSTerm term = xsParticle.getTerm();
			if (term.isElementDecl()) {
				XSElementDecl element = term.asElementDecl();
				printQName(element, indent + "Element: ");
				System.out.println(indent2 + "MinOccurs: " + xsParticle.getMinOccurs());
				System.out.println(indent2 + "MaxOccurs: " + xsParticle.getMaxOccurs());
				XSType type = element.getType();
				if (type.isSimpleType()) {
					print(type.asSimpleType(), indent2);
				} else {
					print(type.asComplexType(), indent2);
				}
			} else if (term.isModelGroup()) {
				print(term.asModelGroup(), indent + "\t");
			}
		}
	}

	private static void print(XSSimpleType simpleType, String indent) {
		printQName(simpleType, indent + "SimpleType: ");
		if (!simpleType.getTargetNamespace().equals(XMLConstants.W3C_XML_SCHEMA_NS_URI)) {
			print(simpleType.getBaseType().asSimpleType(), indent + "\t");
		}
	}

	private static void printQName(XSDeclaration declaration, String indent) {
		QName qName = new QName(declaration.getTargetNamespace(), declaration.getName());
		System.out.println(indent + qName);
	}

	@Test
	public void testJSON() throws Exception {
		Transformer transformer = context.getIdenticalTransformer();
		transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		transformer.transform(new SAXSource(new Json2XmlParser(true), new InputSource(new ByteArrayInputStream(readFile("src/test/resources/RESTRequest.json")))), new StreamResult(System.out));
	}

	private XSSchemaSet createXSSchemaSet() throws Exception {
		XSDArtifact xsdArtifact1 = new XSDArtifact(getGlobalContext().getFileSystem(), getGlobalContext().getFileSystem().getRoot(), "de.aoa.xsd.demo.v1.xsd");
		xsdArtifact1.setContent(readFile("src/test/resources/example/de.aoa.xsd.demo.v1.xsd"));
		XSDArtifact xsdArtifact2 = new XSDArtifact(getGlobalContext().getFileSystem(), getGlobalContext().getFileSystem().getRoot(), "de.aoa.ei.foundation.v1.xsd");
		xsdArtifact2.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));

		xsdArtifact1.validate(getGlobalContext());
		assertTrue(xsdArtifact2.isValidated());

		XSOMParser xsomParser = new XSOMParser(JAXPFactoryHelper.getSAXParserFactory());
		xsomParser.setEntityResolver(xsdArtifact1.getResolver());
		InputSource is = new InputSource(xsdArtifact1.getContentAsStream());
		is.setSystemId(xsdArtifact1.getURI());
		xsomParser.parse(is);
		Set<SchemaDocument> documents = xsomParser.getDocuments();
		assertEquals(3, documents.size());
		return xsomParser.getResult();
	}
	
	@Test
	public void testJSON2XML() throws Exception {
		XSSchemaSet schemaSet = createXSSchemaSet();
		HashMap<String, String> map = new HashMap<>();
		map.put("", "http://aoa.de/xsd/demo/v1/");
		map.put("ei1", "http://aoa.de/ei/foundation/v1");
		
		Transformer transformer = context.getIdenticalTransformer();
		transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		Json2XmlTransformer json2xml = new Json2XmlTransformer(schemaSet, true, "{http://aoa.de/xsd/demo/v1/}demoElementRequest", true, map);
		ByteArrayInputStream byteStream = new ByteArrayInputStream(readFile("src/test/resources/RESTRequest.json"));
		transformer.transform(new SAXSource(json2xml.createParser(), new InputSource(byteStream)), new StreamResult(System.out));

		StringWriter writer = new StringWriter();
		byteStream.reset();
		Xml2JsonTransformer xml2JsonTransformer = new Xml2JsonTransformer(schemaSet, "{http://aoa.de/xsd/demo/v1/}demoElementRequest", true, map);
		SAXResult result = new SAXResult(xml2JsonTransformer.createTransformerHandler(writer));
		//transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		transformer.transform(new SAXSource(json2xml.createParser(), new InputSource(byteStream)), result);
		System.out.println(writer);
		
		// Performance
//		StreamResult streamResult = new StreamResult(writer);
//		TimeGauge timeGauge = new TimeGauge(Action.logger, 0L, false);
//		timeGauge.startTimeMeasurement();
//		int count = 10000000;
//		for (int i = 0; i < count; ++i) {
//			byteStream.reset();
//			writer.reset();
//			SAXResult saxResult = new SAXResult(xml2JsonTransformer.createTransformerHandler(writer));
//			transformer.transform(new SAXSource(json2xml.createParser(), new InputSource(byteStream)), saxResult);
//		}
//		long measurement = timeGauge.stopTimeMeasurement("Performance", false);
//		System.out.println("Average in µs: " + measurement * 1000 / count);
	}

	@Test
	public void testJSON2XMLAnyComplex() throws Exception {
		XSSchemaSet schemaSet = createXSSchemaSet();
		HashMap<String, String> map = new HashMap<>();
		map.put("", "http://aoa.de/xsd/demo/v1/");
		map.put("ei1", "http://aoa.de/ei/foundation/v1");
		
		Transformer transformer = context.getIdenticalTransformer();
		transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		Json2XmlTransformer json2xml = new Json2XmlTransformer(schemaSet, true, "{http://aoa.de/xsd/demo/v1/}demoElementRequest", true, map);
		ByteArrayInputStream byteStream = new ByteArrayInputStream(readFile("src/test/resources/RESTRequest_AnyComplex.json"));
		transformer.transform(new SAXSource(json2xml.createParser(), new InputSource(byteStream)), new StreamResult(System.out));

		StringWriter writer = new StringWriter();
		byteStream.reset();
		Xml2JsonTransformer xml2JsonTransformer = new Xml2JsonTransformer(schemaSet, "{http://aoa.de/xsd/demo/v1/}demoElementRequest", true, map);
		SAXResult result = new SAXResult(xml2JsonTransformer.createTransformerHandler(writer));
		transformer.transform(new SAXSource(json2xml.createParser(), new InputSource(byteStream)), result);
		System.out.println(writer);
		
		// Performance
//		StreamResult streamResult = new StreamResult(writer);
//		TimeGauge timeGauge = new TimeGauge(Action.logger, 0L, false);
//		timeGauge.startTimeMeasurement();
//		for (int i = 0; i < 1000000; ++i) {
//			byteStream.reset();
//			writer.reset();
//			//SAXResult saxResult = new SAXResult(xml2JsonTransformer.createTransformerHandler(writer));
//			transformer.transform(new SAXSource(json2xml.createParser(), new InputSource(byteStream)), streamResult);
//		}
//		timeGauge.stopTimeMeasurement("Performance", false);
	}


	@Test
	public void testJSONwoRoot2XML() throws Exception {
		XSSchemaSet schemaSet = createXSSchemaSet();
		HashMap<String, String> map = new HashMap<>();
		map.put("", "http://aoa.de/xsd/demo/v1/");
		map.put("ei1", "http://aoa.de/ei/foundation/v1");
		
		Transformer transformer = context.getIdenticalTransformer();
		transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		Json2XmlTransformer json2xml = new Json2XmlTransformer(schemaSet, true, "{http://aoa.de/xsd/demo/v1/}demoElementRequest", false, map);
		ByteArrayInputStream byteStream = new ByteArrayInputStream(readFile("src/test/resources/RESTRequest_woRoot.json"));
		transformer.transform(new SAXSource(json2xml.createParser(), new InputSource(byteStream)), new StreamResult(System.out));

		byteStream.reset();
		StringWriter writer = new StringWriter();
		Xml2JsonTransformer xml2JsonTransformer = new Xml2JsonTransformer(schemaSet, "{http://aoa.de/xsd/demo/v1/}demoElementRequest", false, map);
		SAXResult result = new SAXResult(xml2JsonTransformer.createTransformerHandler(writer));
		transformer.transform(new SAXSource(json2xml.createParser(), new InputSource(byteStream)), result);
		System.out.println(writer);
		
		// Performance
//		StreamResult streamResult = new StreamResult(writer);
//		TimeGauge timeGauge = new TimeGauge(Action.logger, 0L, false);
//		timeGauge.startTimeMeasurement();
//		for (int i = 0; i < 1000000; ++i) {
//			byteStream.reset();
//			writer.reset();
//			//SAXResult saxResult = new SAXResult(xml2JsonTransformer.createTransformerHandler(writer));
//			transformer.transform(new SAXSource(json2xml.createParser(), new InputSource(byteStream)), streamResult);
//		}
//		timeGauge.stopTimeMeasurement("Performance", false);
	}

	@Test
	public void testJSON2XMLwoSchema() throws Exception {
		Transformer transformer = context.getIdenticalTransformer();
		transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		Json2XmlTransformer json2xml = new Json2XmlTransformer(null, true, "{http://aoa.de/xsd/demo/v1/}demoElementRequest", false, null);
		ByteArrayInputStream byteStream = new ByteArrayInputStream(readFile("src/test/resources/RESTRequest.json"));
		transformer.transform(new SAXSource(json2xml.createParser(), new InputSource(byteStream)), new StreamResult(System.out));

		StringWriter writer = new StringWriter();
		byteStream.reset();
		Xml2JsonTransformer xml2JsonTransformer = new Xml2JsonTransformer(null, "{http://aoa.de/xsd/demo/v1/}demoElementRequest", false, null);
		SAXResult result = new SAXResult(xml2JsonTransformer.createTransformerHandler(writer));
		transformer.transform(new SAXSource(json2xml.createParser(), new InputSource(byteStream)), result);
		System.out.println(writer);
		
		// Performance
//		StreamResult streamResult = new StreamResult(writer);
//		TimeGauge timeGauge = new TimeGauge(Action.logger, 0L, false);
//		timeGauge.startTimeMeasurement();
//		for (int i = 0; i < 1000000; ++i) {
//			byteStream.reset();
//			writer.reset();
//			//SAXResult saxResult = new SAXResult(xml2JsonTransformer.createTransformerHandler(writer));
//			transformer.transform(new SAXSource(json2xml.createParser(), new InputSource(byteStream)), streamResult);
//		}
//		timeGauge.stopTimeMeasurement("Performance", false);
	}

	@Test
	public void testJSON2XMLwoSchemaAnyComplex() throws Exception {
		Transformer transformer = context.getIdenticalTransformer();
		transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		Json2XmlTransformer json2xml = new Json2XmlTransformer(null, true, "{http://aoa.de/xsd/demo/v1/}demoElementRequest", false, null);
		ByteArrayInputStream byteStream = new ByteArrayInputStream(readFile("src/test/resources/RESTRequest_AnyComplex.json"));
		transformer.transform(new SAXSource(json2xml.createParser(), new InputSource(byteStream)), new StreamResult(System.out));

		StringWriter writer = new StringWriter();
		byteStream.reset();
		Xml2JsonTransformer xml2JsonTransformer = new Xml2JsonTransformer(null, "{http://aoa.de/xsd/demo/v1/}demoElementRequest", false, null);
		SAXResult result = new SAXResult(xml2JsonTransformer.createTransformerHandler(writer));
		transformer.transform(new SAXSource(json2xml.createParser(), new InputSource(byteStream)), result);
		System.out.println(writer);
		
		// Performance
//		StreamResult streamResult = new StreamResult(writer);
//		TimeGauge timeGauge = new TimeGauge(Action.logger, 0L, false);
//		timeGauge.startTimeMeasurement();
//		for (int i = 0; i < 1000000; ++i) {
//			byteStream.reset();
//			writer.reset();
//			//SAXResult saxResult = new SAXResult(xml2JsonTransformer.createTransformerHandler(writer));
//			transformer.transform(new SAXSource(json2xml.createParser(), new InputSource(byteStream)), streamResult);
//		}
//		timeGauge.stopTimeMeasurement("Performance", false);
	}

	@Test
	public void testXMLSample() throws Exception {
		XSSchemaSet schemaSet = createXSSchemaSet();
		XmlSampleGenerator generator = new XmlSampleGenerator(schemaSet, "{http://aoa.de/xsd/demo/v1/}demoElementRequest");
		Transformer transformer = context.getIdenticalTransformer();
		transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		transformer.transform(new SAXSource(generator, null),  new StreamResult(System.out));
	}
	
	
}
