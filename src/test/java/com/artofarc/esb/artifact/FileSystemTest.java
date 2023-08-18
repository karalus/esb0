package com.artofarc.esb.artifact;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.*;
import org.junit.Test;

import com.artofarc.esb.AbstractESBTest;
import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.action.TerminalAction;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.PoolContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;

public class FileSystemTest extends AbstractESBTest {

   @Test
   public void testFileSystem() throws Exception {
      FileSystem fileSystem = new FileSystemDir("src/test/resources");
      fileSystem.init(context.getPoolContext().getGlobalContext());
      FileSystem clone = fileSystem.copy();
      assertFalse(fileSystem.getRoot() == clone.getRoot());
      assertFalse(fileSystem.tidyOut(clone.new ChangeSet()));
   }
   
  @Test
   public void testStartup() throws Exception {
      try (GlobalContext globalContext = new GlobalContext(null)) {
         DeployHelper.attachFileSystemAndDeploy(globalContext, "src/test/resources");
         ConsumerPort service = globalContext.getInternalService("/HttpService4.xservice");
         assertNotNull(service);
         service = globalContext.getHttpService("/demo1");
         assertNotNull(service);
      }
   }
   
   @Test
   public void testRealService() throws Exception {
      try (GlobalContext globalContext = new GlobalContext(null)) {
          DeployHelper.attachFileSystemAndDeploy(globalContext, "src/test/resources");
         ConsumerPort service = globalContext.getInternalService("/example/ExampleService.xservice");
         assertNotNull(service);
         service = globalContext.getHttpService("/exampleUsingport");
         assertNotNull(service);
         // Call
         ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
         message.getVariables().put(ESBConstants.HttpMethod, "POST");
         message.setContentType(HttpConstants.HTTP_HEADER_CONTENT_TYPE_SOAP11);
         message.putHeader(HttpConstants.HTTP_HEADER_SOAP_ACTION, "\"\"");
         message.getVariables().put("hasFault", false);
         try {
            PoolContext poolContext = globalContext.getDefaultWorkerPool().getPoolContext();
				service.process(new Context(poolContext ), message);
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
