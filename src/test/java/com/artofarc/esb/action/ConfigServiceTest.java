package com.artofarc.esb.action;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import javax.jws.WebService;
import javax.jws.soap.SOAPBinding;
import javax.xml.soap.SOAPConstants;
import javax.xml.ws.Endpoint;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.artifact.ServiceArtifact;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.PoolContext;
import com.artofarc.esb.context.WorkerPool;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.ESBVariableConstants;


public class ConfigServiceTest {
   
   private Context context;
   
   @Before
   public void createContext() throws Exception {
      GlobalContext globalContext = new GlobalContext();
      // default WorkerPool
      globalContext.putWorkerPool(null, new WorkerPool(globalContext));      
      context = new Context(new PoolContext(globalContext));
   }

   @After
   public void closeContext() throws Exception {
      if (context != null) {
         context.close();
      }
   }
   
   protected static byte[] readFile(String fileName) throws IOException {
      File file = new File(fileName);
      byte[] ba = new byte[(int) file.length()];
      try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
         dis.readFully(ba);
      }
      return ba;
   }

   @WebService
   @SOAPBinding(style=SOAPBinding.Style.DOCUMENT)
   public static class Echo {
      public String checkAlive(String text) throws Exception {
         Thread.sleep(500L);
         return text;
      }
   }   

   @WebService
   @SOAPBinding(style=SOAPBinding.Style.DOCUMENT)
   public static class Echo2 {
      public String other(String text) throws Exception {
         Thread.sleep(500L);
         return text;
      }
   }   

   @Test
   public void testHTTPService() throws Exception {
      ServiceArtifact serviceArtifact = new ServiceArtifact(null, null);
      serviceArtifact.setContent(readFile("src/test/resources//HttpService4.xservice"));
      serviceArtifact.validate(null);
      ConsumerPort consumerPort = serviceArtifact.getConsumerPort();
      
      String url = "http://localhost:1212/echo";
      Endpoint endpoint = Endpoint.publish(url, new Echo());
      System.out.println("Service started @ " + url);
      //
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest2.xml"));
      message.getVariables().put(ESBVariableConstants.HttpMethod, "POST");
      message.getHeaders().put(HttpOutboundAction.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.getHeaders().put(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
      consumerPort.processInternal(context, message);
      endpoint.stop();
   }

   @Test
   public void testValidation() throws Exception {
      ServiceArtifact serviceArtifact = new ServiceArtifact(null, null);
      serviceArtifact.setContent(readFile("src/test/resources/HttpService4.xservice"));
      serviceArtifact.validate(null);
      ConsumerPort consumerPort = serviceArtifact.getConsumerPort();
      
      String url = "http://localhost:1212/echo";
      Endpoint endpoint = Endpoint.publish(url, new Echo());
      System.out.println("Service started @ " + url);
      //
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest2.xml"));
      message.getVariables().put(ESBVariableConstants.HttpMethod, "POST");
      message.getHeaders().put(HttpOutboundAction.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.getHeaders().put(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
      consumerPort.processInternal(context, message);
      endpoint.stop();
   }

}
