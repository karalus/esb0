package com.artofarc.esb.action;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

import static org.junit.Assert.*;
import org.junit.Test;

import com.artofarc.esb.AbstractESBTest;
import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.artifact.FileSystem;
import com.artofarc.esb.artifact.FileSystemDir;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.PoolContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.servlet.ESBServletContextListener;


public class FileSystemTest extends AbstractESBTest {

   @Test
   public void testFileSystem() throws Exception {
      File dir = new File("src/test/resources");
      assertTrue(dir.exists());
      FileSystem fileSystem = new FileSystemDir(dir);
      fileSystem.init(context.getPoolContext().getGlobalContext());
      FileSystem clone = fileSystem.copy();
      assertFalse(fileSystem.getRoot() == clone.getRoot());
      assertFalse(fileSystem.tidyOut());
   }
   
  @Test
   public void testStartup() {
		try (GlobalContext globalContext = new ESBServletContextListener().createContext(getClass().getClassLoader(), "src/test/resources", new Properties())) {
         ConsumerPort service = globalContext.getInternalService("/HttpService4.xservice");
         assertNotNull(service);
         service = globalContext.getHttpService("/demo1");
         assertNotNull(service);
      }
   }
   
   @Test
   public void testRealService() throws Exception {
		try (GlobalContext globalContext = new ESBServletContextListener().createContext(getClass().getClassLoader(), "src/test/resources", new Properties())) {
         ConsumerPort service = globalContext.getInternalService("/example/ExampleService.xservice");
         assertNotNull(service);
         service = globalContext.getHttpService("/exampleUsingport");
         assertNotNull(service);
         // Call
         ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
         message.getVariables().put(ESBConstants.HttpMethod, "POST");
         message.setContentType(HttpConstants.SOAP_1_1_CONTENT_TYPE);
         message.putHeader(HttpConstants.HTTP_HEADER_SOAP_ACTION, "\"\"");
         message.getVariables().put("hasFault", false);
         try {
            PoolContext poolContext = globalContext.getDefaultWorkerPool().getPoolContext();
				service.processInternal(new Context(poolContext ), message);
         } catch (IOException e) {
		      // ignore
         }
         Boolean dynamic = message.getVariable("dynamic");
         assertEquals(Boolean.TRUE, dynamic);
      }
   }
   
	public static class DynamicAction extends TerminalAction {

		@Override
		protected void execute(Context context, ESBMessage message) throws Exception {
			message.putVariable("dynamic", true);
		}
	}
   
}
