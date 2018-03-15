package com.artofarc.esb.action;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;

import javax.xml.soap.SOAPConstants;

import org.junit.Test;

import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.artifact.FileSystem;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.PoolContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.ESBVariableConstants;
import com.artofarc.esb.servlet.ESBServletContextListener;


public class FileSystemTest {

   @Test
   public void testFileSystem() throws Exception {
      File dir = new File("src/test/resources");
      assertTrue(dir.exists());
      FileSystem fileSystem = new FileSystem();
      fileSystem.parseDirectory(null, dir);
      FileSystem clone = fileSystem.clone();
      assertFalse(fileSystem.getRoot() == clone.getRoot());
      assertFalse(fileSystem.tidyOut());
   }
   
  @Test
   public void testStartup() {
      try (PoolContext poolContext = ESBServletContextListener.createGlobalAndDefaultPoolContext(new File("src/test/resources"))) {
         GlobalContext globalContext = poolContext.getGlobalContext();
         ConsumerPort service = globalContext.getInternalService("/HttpService4.xservice");
         assertNotNull(service);
         service = globalContext.getHttpService("/demo1");
         assertNotNull(service);
      }
   }
   
   @Test
   public void testRealService() throws Exception {
      try (PoolContext poolContext = ESBServletContextListener.createGlobalAndDefaultPoolContext(new File("src/test/resources"))) {
         GlobalContext globalContext = poolContext.getGlobalContext();
         ConsumerPort service = globalContext.getInternalService("/example/ExampleService.xservice");
         assertNotNull(service);
         service = globalContext.getHttpService("/exampleUsingport");
         assertNotNull(service);
         // Call
         ESBMessage message = new ESBMessage(BodyType.BYTES, ConfigServiceTest.readFile("src/test/resources/SOAPRequest.xml"));
         message.getVariables().put(ESBVariableConstants.HttpMethod, "POST");
         message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
         message.getHeaders().put(HttpOutboundAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
         message.getVariables().put(ESBVariableConstants.hasFault, false);
         try {
            service.processInternal(new Context(poolContext), message);
         } catch (IOException e) {
		      // ignore
         }
      }
   }
   
}
