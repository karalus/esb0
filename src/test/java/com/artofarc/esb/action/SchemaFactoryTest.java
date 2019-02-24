package com.artofarc.esb.action;

import static org.junit.Assert.*;

import java.util.Collection;
import java.util.Map.Entry;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.validation.Schema;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.artofarc.esb.AbstractESBTest;
import com.artofarc.esb.artifact.SchemaArtifact;
import com.artofarc.esb.artifact.WSDLArtifact;
import com.artofarc.esb.artifact.XMLCatalog;
import com.artofarc.esb.artifact.XSDArtifact;
import com.artofarc.util.ReflectionUtils;


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
		XMLCatalog.attachToFileSystem(getGlobalContext());
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
		wsdlArtifact.validate(getGlobalContext());
		printSchemaInternals(soap11);
		printSchemaInternals(wsdlArtifact);

	}
	
	private static void printSchemaInternals(SchemaArtifact schemaArtifact) throws ReflectiveOperationException {
		System.out.println("Internals of: " + schemaArtifact.getURI());
		printGrammars(schemaArtifact.getSchema());
		Collection<String> collection = schemaArtifact.getReferenced();
		printReferencedGrammars(schemaArtifact);
		for (String referenced : collection) {
			SchemaArtifact artifact = schemaArtifact.getArtifact(referenced);
			printReferencedGrammars(artifact);
			//System.out.println("Referenced: " + artifact.getURI() + ": " + System.identityHashCode(artifact.getGrammar()));
		}
	}
	
	public static void printGrammars(Schema schema) throws ReflectiveOperationException {
		Object[] grammars = ReflectionUtils.eval(schema, "grammarPool.retrieveInitialGrammarSet($1)", XMLConstants.W3C_XML_SCHEMA_NS_URI);
		for (Object grammar : grammars) {
			System.out.println(ReflectionUtils.eval(grammar, "grammarDescription.baseSystemId") + "->"
					+ ReflectionUtils.eval(grammar, "grammarDescription.literalSystemId") + ": " + System.identityHashCode(grammar));
		}
	}
	
	private static void printReferencedGrammars(SchemaArtifact schemaArtifact) {
		System.out.println("Grammars of " + schemaArtifact.getURI());
		Set<Entry<String,Object>> entrySet = schemaArtifact.getGrammars().entrySet();
		for (Entry<String, Object> entry : entrySet) {
			System.out.println("Namespace: " + entry.getKey() + ": " + System.identityHashCode(entry.getValue()));
		}
	}

//	@Test
//	public void testCacheGrammars1() throws Exception {
//		XSDArtifact xsdArtifact1 = new XSDArtifact(null, null, "kdf");
//		xsdArtifact1.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
//		xsdArtifact1.validateInternal(getGlobalContext());
//		XMLGrammarPool schema1 = (XMLGrammarPool) xsdArtifact1.getSchema();
//		Grammar[] grammars1 = schema1.retrieveInitialGrammarSet(XMLGrammarDescription.XML_SCHEMA);
//		XSDArtifact xsdArtifact2 = new XSDArtifact(null, null, "kdf");
//		xsdArtifact2.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
//		xsdArtifact2.validateInternal(getGlobalContext());
//		XMLGrammarPool schema2 = (XMLGrammarPool) xsdArtifact2.getSchema();
//		Grammar[] grammars2 = schema2.retrieveInitialGrammarSet(XMLGrammarDescription.XML_SCHEMA);
//		XSDArtifact xsdArtifact3 = new XSDArtifact(null, null, "other");
//		xsdArtifact3.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
//		xsdArtifact3.validateInternal(getGlobalContext());
//		XMLGrammarPool schema3 = (XMLGrammarPool) xsdArtifact3.getSchema();
//		Grammar[] grammars3 = schema3.retrieveInitialGrammarSet(XMLGrammarDescription.XML_SCHEMA);
//		assertTrue(grammars1[0] == grammars2[0]);
//		assertTrue(grammars1[0] != grammars3[0]);
//	}
//
}
