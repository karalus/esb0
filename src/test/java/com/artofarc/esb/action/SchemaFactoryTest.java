package com.artofarc.esb.action;

import static org.junit.Assert.*;

import java.util.Collection;
import java.util.Map.Entry;
import java.util.Set;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.artofarc.esb.AbstractESBTest;
import com.artofarc.esb.artifact.SchemaArtifact;
import com.artofarc.esb.artifact.WSDLArtifact;
import com.artofarc.esb.artifact.XMLCatalog;
import com.artofarc.esb.artifact.XSDArtifact;
import com.artofarc.util.SchemaUtils;


public class SchemaFactoryTest extends AbstractESBTest {
	
	@BeforeClass
	public static void init() {
		System.setProperty("esb0.cacheXSGrammars", "true");
	}

	@AfterClass
	public static void destroy() {
		System.setProperty("esb0.cacheXSGrammars", "false");
	}

	@Test
	public void testCacheGrammars() throws Exception {
		XMLCatalog.attachToFileSystem(getGlobalContext().getFileSystem());
		XSDArtifact soap11 = getGlobalContext().getFileSystem().getArtifact(XMLCatalog.PATH + "/soap11.xsd");
		soap11.clearContent();
		assertNotNull(soap11.getContent());
		
		XSDArtifact xsdArtifact1 = new XSDArtifact(getGlobalContext().getFileSystem(), getGlobalContext().getFileSystem().getRoot(), "de.aoa.xsd.demo.v1.xsd");
		xsdArtifact1.setContent(readFile("src/test/resources/example/de.aoa.xsd.demo.v1.xsd"));
		XSDArtifact xsdArtifact2 = new XSDArtifact(getGlobalContext().getFileSystem(), getGlobalContext().getFileSystem().getRoot(), "de.aoa.ei.foundation.v1.xsd");
		xsdArtifact2.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));

		xsdArtifact1.validate(getGlobalContext());
		assertTrue(xsdArtifact2.isValidated());

		printSchemaInternals(xsdArtifact1);
		printSchemaInternals(xsdArtifact2);
		System.out.println("---");
		
		WSDLArtifact wsdlArtifact = new WSDLArtifact(getGlobalContext().getFileSystem(), getGlobalContext().getFileSystem().getRoot(), "example.wsdl");
		wsdlArtifact.setContent(readFile("src/test/resources/example/example.wsdl"));
//		WSDLArtifact wsdlArtifact1 = new WSDLArtifact(getGlobalContext().getFileSystem(), getGlobalContext().getFileSystem().getRoot(), "exampleConcrete.wsdl");
//		wsdlArtifact1.setContent(readFile("src/test/resources/example/exampleConcrete.wsdl"));
//		WSDLArtifact wsdlArtifact2 = new WSDLArtifact(getGlobalContext().getFileSystem(), getGlobalContext().getFileSystem().getRoot(), "exampleAbstract.wsdl");
//		wsdlArtifact2.setContent(readFile("src/test/resources/example/exampleAbstract.wsdl"));
		wsdlArtifact.validate(getGlobalContext());
		printSchemaInternals(soap11);
		printSchemaInternals(wsdlArtifact);
	}
	
	private static void printSchemaInternals(SchemaArtifact schemaArtifact) throws ReflectiveOperationException {
		System.out.println("Internals of: " + schemaArtifact.getURI());
		SchemaUtils.printGrammars(schemaArtifact.getSchema(), System.out);
		Collection<String> collection = schemaArtifact.getReferenced();
		printReferencedGrammars(schemaArtifact);
		for (String referenced : collection) {
			SchemaArtifact artifact = schemaArtifact.getArtifact(referenced);
			printReferencedGrammars(artifact);
			//System.out.println("Referenced: " + artifact.getURI() + ": " + System.identityHashCode(artifact.getGrammar()));
		}
	}
	
	private static void printReferencedGrammars(SchemaArtifact schemaArtifact) {
		System.out.println("Grammars of " + schemaArtifact.getURI());
		Set<Entry<String,Object>> entrySet = schemaArtifact.getGrammars().entrySet();
		for (Entry<String, Object> entry : entrySet) {
			System.out.println("Namespace: " + entry.getKey() + ": " + System.identityHashCode(entry.getValue()));
		}
	}

}
