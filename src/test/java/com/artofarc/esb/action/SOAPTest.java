package com.artofarc.esb.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

import javax.jws.WebService;
import javax.jws.soap.SOAPBinding;
import javax.xml.soap.SOAPConstants;
import javax.xml.ws.Endpoint;

import org.junit.Test;
import org.w3c.dom.Node;

import com.artofarc.esb.AbstractESBTest;
import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.TimerService;
import com.artofarc.esb.artifact.XQueryArtifact;
import com.artofarc.esb.artifact.XSDArtifact;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.jms.JMSConsumer;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.ESBVariableConstants;
import com.artofarc.util.Collections;


public class SOAPTest extends AbstractESBTest {
   
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
   public void testUnwrapSOAP11() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
      Action action = new UnwrapSOAPAction(false, true);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(new WrapSOAPAction(false, true));
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
      assertEquals("demoElementRequest", message.getVariable(ESBVariableConstants.SOAP_OPERATION));
      @SuppressWarnings("unused")
		Node node = message.getVariable("header");
      System.out.println();
      //context.getIdenticalTransformer().transform(new DOMSource(node), new StreamResult(System.out));
      node = message.getVariable("messageHeader");
      System.out.println();
      //context.getIdenticalTransformer().transform(new DOMSource(node), new StreamResult(System.out));
      System.out.println();
   }
   
   @Test
   public void testHTTP() throws Exception {
      String url = "http://localhost:1212/echo";
      Endpoint endpoint = Endpoint.publish(url, new Echo());
      System.out.println("Service started @ " + url);
      //
      Action action = new AssignAction("request", ".");
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(new HttpOutboundAction(url));
      action = action.setNextAction(new HttpInboundAction());
      action = action.setNextAction(new AssignAction("response", "."));
      action = action.setNextAction(new DumpAction());
      //
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest2.xml"));
      message.getVariables().put(ESBVariableConstants.HttpMethod, "POST");
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.getHeaders().put(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
      consumerPort.process(context, message);
      endpoint.stop();
   }

   @Test
   public void testHTTPDecoupleRead() throws Exception {
      String url = "http://localhost:1212/echo";
      Endpoint endpoint = Endpoint.publish(url, new Echo());
      System.out.println("Service started @ " + url);
      //
      Action action = new AssignAction("request", ".");
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(new HttpOutboundAction(url));
      action = action.setNextAction(new SpawnAction(null, false));
      action = action.setNextAction(new HttpInboundAction());
      action = action.setNextAction(new AssignAction("response", "."));
      action = action.setNextAction(new DumpAction());
      //
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest2.xml"));
      message.getVariables().put(ESBVariableConstants.HttpMethod, "POST");
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.getHeaders().put(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
      consumerPort.process(context, message);
      endpoint.stop();
   }

   @Test
   public void testHTTPDecoupled() throws Exception {
      String url = "http://localhost:1212/echo";
      Endpoint endpoint = Endpoint.publish(url, new Echo());
      System.out.println("Service started @ " + url);
      //
      Action action = new AssignAction("request", ".");
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(new SpawnAction(null, false));
      //action = action.setNextAction(new AssignAction("request", "."));
      action = action.setNextAction(new HttpOutboundAction(url));
      action = action.setNextAction(new HttpInboundAction());
      //action = action.setNextAction(new AssignAction("response", "."));
      action = action.setNextAction(new DumpAction());
      //
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest2.xml"));
      message.getVariables().put(ESBVariableConstants.HttpMethod, "POST");
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.getHeaders().put(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
      consumerPort.process(context, message);
      endpoint.stop();
   }

   @Test
   public void testHTTPDecoupledUsingPipe() throws Exception {
      String url = "http://localhost:1212/echo";
      Endpoint endpoint = Endpoint.publish(url, new Echo());
      System.out.println("Service started @ " + url);
      //
      Action action = new AssignAction("request", ".");
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setTerminalAction(new DumpAction());
      consumerPort.setStartAction(action);
      action = action.setNextAction(new SpawnAction(null, true));
      //action = action.setNextAction(new AssignAction("request", "."));
      action = action.setNextAction(new HttpOutboundAction(url));
      action = action.setNextAction(new HttpInboundAction());
      //action = action.setNextAction(new AssignAction("response", "."));
      //action = action.setNextAction(new DumpAction());
      //
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest2.xml"));
      message.getVariables().put(ESBVariableConstants.HttpMethod, "POST");
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.getHeaders().put(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
      consumerPort.process(context, message);
      endpoint.stop();
   }

   @Test
   public void testHTTPFault() throws Exception {
      String url = "http://localhost:1212/echo";
      Endpoint endpoint = Endpoint.publish(url, new Echo());
      System.out.println("Service started @ " + url);
      //
      Action action = new HttpOutboundAction("http://localhost:1212/e");
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(new HttpInboundAction());
      action = action.setNextAction(new PostSOAPHttpAction(false, false));
      action = action.setNextAction(new DumpAction());
      //
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest2.xml"));
      message.getVariables().put(ESBVariableConstants.HttpMethod, "POST");
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.getHeaders().put(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
      try {
         consumerPort.process(context, message);
         fail();
      } catch (ExecutionException e) {
         // expected
      }
      endpoint.stop();
   }

   @Test
   public void testHTTPFaultWithErrorHandler() throws Exception {
      String url = "http://localhost:1212/echo";
      Endpoint endpoint = Endpoint.publish(url, new Echo());
      System.out.println("Service started @ " + url);
      //
      Action action = new HttpOutboundAction("http://localhost:1212/e");
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(new HttpInboundAction());
      action = action.setNextAction(new PostSOAPHttpAction(false, false));
      MarkAction errorHandler = new MarkAction();
      action.setErrorHandler(errorHandler);
      action = action.setNextAction(new DumpAction());
      //
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest2.xml"));
      message.getVariables().put(ESBVariableConstants.HttpMethod, "POST");
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.getHeaders().put(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
      consumerPort.process(context, message);
      assertTrue(errorHandler.executed);
      endpoint.stop();
   }

   @Test
   public void testValidate() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
      Action action = new UnwrapSOAPAction(false, true);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(new TransformAction("declare namespace v1=\"http://aoa.de/ei/foundation/v1\"; v1:messageHeader"));
      XSDArtifact xsdArtifact = new XSDArtifact(null, "kdf");
      xsdArtifact.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
      xsdArtifact.validateInternal(null);
      action = action.setNextAction(new ValidateAction(xsdArtifact.getSchema(), ".", null));
      action = action.setNextAction(new ValidateAction(xsdArtifact.getSchema(), ".", null));
      action = action.setNextAction(new ValidateAction(xsdArtifact.getSchema(), ".", null));
      action = action.setNextAction(new ValidateAction(xsdArtifact.getSchema(), ".", null));
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
      assertEquals("demoElementRequest", message.getVariable(ESBVariableConstants.SOAP_OPERATION));
   }
   
   @Test
   public void testValidateDirect() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
      Action action = new UnwrapSOAPAction(false, true);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      XSDArtifact xsdArtifact = new XSDArtifact(null, "kdf");
      xsdArtifact.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
      xsdArtifact.validateInternal(null);
      List<Entry<String, String>> result = new ArrayList<>();
      result.add(Collections.createEntry("v1", "http://aoa.de/ei/foundation/v1"));
      action = action.setNextAction(new ValidateAction(xsdArtifact.getSchema(), "v1:messageHeader", result));
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
      assertEquals("demoElementRequest", message.getVariable(ESBVariableConstants.SOAP_OPERATION));
   }
   
   @Test
   public void testTransform() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
      Action action = new UnwrapSOAPAction(false, true);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(new AssignAction("request", "."));
      XQueryArtifact xqueryArtifact = new XQueryArtifact(null, null);
      xqueryArtifact.setContent("declare variable $request as element() external; $request".getBytes());
      xqueryArtifact.validateInternal(context.getPoolContext().getGlobalContext());
      TransformAction nextAction = new TransformAction(xqueryArtifact.getXQuery());
      action = action.setNextAction(nextAction);
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
      assertEquals("demoElementRequest", message.getVariable(ESBVariableConstants.SOAP_OPERATION));
   }
   
   public void testJMSConsumer() throws Exception {
      JMSConsumer jmsConsumer = new JMSConsumer(null, "ConnectionFactory", "dynamicQueues/test1", null, null, null, 1);
      MarkAction markAction = new MarkAction();
      jmsConsumer.setStartAction(markAction);
      jmsConsumer.init(context.getPoolContext());
      
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest1.xml"));
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
      
      JMSAction jmsAction = new JMSAction(context.getPoolContext().getGlobalContext(), "ConnectionFactory", "dynamicQueues/test1", null, null, false, 4, 100);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(jmsAction);
      consumerPort.process(context, message);
      assertFalse(markAction.executed);

      Thread.sleep(100);
      
      assertTrue(markAction.executed);
      
      jmsConsumer.destroy();
   }
   
   @Test
   public void testTimerService() throws Exception {
      TimerService timerService = new TimerService(null, null, 0, 100, false);
      MarkAction markAction = new MarkAction();
      timerService.setStartAction(markAction);
      timerService.init(context.getPoolContext().getGlobalContext());
      timerService.enable(true);
      assertFalse(markAction.executed);
      Thread.sleep(100);
      timerService.enable(false);
      assertTrue(markAction.executed);
      
   }
}
