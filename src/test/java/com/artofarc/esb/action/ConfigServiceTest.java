package com.artofarc.esb.action;

import javax.jws.WebService;
import javax.jws.soap.SOAPBinding;
import javax.xml.soap.SOAPConstants;
import javax.xml.ws.Endpoint;

import org.junit.Test;

import com.artofarc.esb.AbstractESBTest;
import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.artifact.ServiceArtifact;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.ESBConstants;


public class ConfigServiceTest extends AbstractESBTest {
   
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
      ServiceArtifact serviceArtifact = new ServiceArtifact(getGlobalContext().getFileSystem(), getGlobalContext().getFileSystem().getRoot(), "HttpService4.xservice");
      serviceArtifact.setContent(readFile("src/test/resources/HttpService4.xservice"));
      serviceArtifact.validate(getGlobalContext());
      ConsumerPort consumerPort = serviceArtifact.getConsumerPort();
      
      String url = "http://localhost:1212/echo";
      Endpoint endpoint = Endpoint.publish(url, new Echo());
      System.out.println("Service started @ " + url);
      //
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest2.xml"));
      message.getVariables().put(ESBConstants.HttpMethod, "POST");
      message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.getHeaders().put(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
      consumerPort.process(context, message);
      endpoint.stop();
   }

}
