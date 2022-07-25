package com.artofarc.esb.action;

import java.io.File;

import org.junit.Test;

import com.artofarc.esb.AbstractESBTest;
import com.artofarc.esb.artifact.FileSystem;
import com.artofarc.esb.artifact.XSDArtifact;
import com.artofarc.esb.util.XSDGarbageCollector;
import com.sun.xml.xsom.XSSchemaSet;

public class XSDGarbageCollectorTest extends AbstractESBTest {

	@Test
	public void testXSDStrip() throws Exception {
		createContext(new File("src/test/resources/example/"));
		FileSystem fileSystem = getGlobalContext().getFileSystem();
		fileSystem.init(getGlobalContext());
		XSDArtifact xsd = fileSystem.getArtifact("de.aoa.xsd.demo.v1.xsd");
		XSSchemaSet schemaSet = xsd.getXSSchemaSet();
		XSDGarbageCollector generator = new XSDGarbageCollector();
		generator.addSourceSchemaSet(schemaSet);
		generator.addElement("{http://aoa.de/xsd/demo/v1/}demoElementRequest");
		generator.dump();
	}

	@Test
	public void testXSDStripServiceXSD() throws Exception {
		createContext(new File("src/main/xsd/"));
		FileSystem fileSystem = getGlobalContext().getFileSystem();
		fileSystem.init(getGlobalContext());
		XSDArtifact xsd = fileSystem.getArtifact("service.xsd");
		XSSchemaSet schemaSet = xsd.getXSSchemaSet();
		XSDGarbageCollector generator = new XSDGarbageCollector();
		generator.addSourceSchemaSet(schemaSet);
		generator.addElement("{http://www.artofarc.com/esb/service}service");
		generator.dump();
	}

}
