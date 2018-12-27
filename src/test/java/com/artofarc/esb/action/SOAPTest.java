package com.artofarc.esb.action;

import static org.junit.Assert.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;

import javax.jms.Message;
import javax.jws.WebService;
import javax.jws.soap.SOAPBinding;
import javax.xml.soap.SOAPConstants;
import javax.xml.ws.Endpoint;

import org.junit.Test;
import org.w3c.dom.Node;

import com.artofarc.esb.AbstractESBTest;
import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.artifact.Directory;
import com.artofarc.esb.artifact.FileSystem;
import com.artofarc.esb.artifact.WSDLArtifact;
import com.artofarc.esb.artifact.XMLArtifact;
import com.artofarc.esb.artifact.XQueryArtifact;
import com.artofarc.esb.artifact.XSDArtifact;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.jms.JMSConsumer;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.ESBConstants;
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
      action = action.setNextAction(new WrapSOAPAction(false, true,true));
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
      assertEquals("demoElementRequest", message.getVariable(ESBConstants.SOAP_OPERATION));
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
      message.getVariables().put(ESBConstants.HttpMethod, "POST");
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
      action = action.setNextAction(new SpawnAction(null, false, true));
      action = action.setNextAction(new HttpInboundAction());
      action = action.setNextAction(new AssignAction("response", "."));
      action = action.setNextAction(new DumpAction());
      //
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest2.xml"));
      message.getVariables().put(ESBConstants.HttpMethod, "POST");
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
      action = action.setNextAction(new SpawnAction(null, false, true));
      //action = action.setNextAction(new AssignAction("request", "."));
      action = action.setNextAction(new HttpOutboundAction(url));
      action = action.setNextAction(new HttpInboundAction());
      //action = action.setNextAction(new AssignAction("response", "."));
      action = action.setNextAction(new DumpAction());
      //
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest2.xml"));
      message.getVariables().put(ESBConstants.HttpMethod, "POST");
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
      context.getExecutionStack().push(new DumpAction());
      consumerPort.setStartAction(action);
      action = action.setNextAction(new SpawnAction(null, true, true));
      //action = action.setNextAction(new AssignAction("request", "."));
      action = action.setNextAction(new HttpOutboundAction(url));
      action = action.setNextAction(new HttpInboundAction());
      //action = action.setNextAction(new AssignAction("response", "."));
      //action = action.setNextAction(new DumpAction());
      //
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest2.xml"));
      message.getVariables().put(ESBConstants.HttpMethod, "POST");
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
      message.getVariables().put(ESBConstants.HttpMethod, "POST");
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
      message.getVariables().put(ESBConstants.HttpMethod, "POST");
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
      assertEquals("demoElementRequest", message.getVariable(ESBConstants.SOAP_OPERATION));
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
      assertEquals("demoElementRequest", message.getVariable(ESBConstants.SOAP_OPERATION));
   }
   
   @Test
   public void testTransform() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
      Action action = new UnwrapSOAPAction(false, true);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(new AssignAction("request", "."));
      XQueryArtifact xqueryArtifact = new XQueryArtifact(context.getPoolContext().getGlobalContext().getFileSystem().getRoot(), null);
      xqueryArtifact.setContent("declare variable $request as element() external; $request".getBytes());
      xqueryArtifact.validateInternal(context.getPoolContext().getGlobalContext());
      TransformAction nextAction = new TransformAction(xqueryArtifact.getXQuery());
      action = action.setNextAction(nextAction);
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
      assertEquals("demoElementRequest", message.getVariable(ESBConstants.SOAP_OPERATION));
   }
   
   @Test
   public void testTransformWithStaticData() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
      Action action = new UnwrapSOAPAction(false, true);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(new AssignAction("request", "."));
      Directory root = context.getPoolContext().getGlobalContext().getFileSystem().getRoot();
      Directory queries = new Directory(root, "queries");
      Directory staticData = new Directory(root, "data");
		XQueryArtifact xqueryArtifact = new XQueryArtifact(queries, null);
      xqueryArtifact.setContent("declare variable $request as element() external; (doc('/data/static.xml')/*[1]/text(), $request)".getBytes());
		XMLArtifact staticXML = new XMLArtifact(staticData, "static.xml");
		staticXML.setContent("<root>Hello World!</root>".getBytes());
      xqueryArtifact.validateInternal(context.getPoolContext().getGlobalContext());
      TransformAction nextAction = new TransformAction(xqueryArtifact.getXQuery());
      action = action.setNextAction(nextAction);
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
      assertEquals("demoElementRequest", message.getVariable(ESBConstants.SOAP_OPERATION));
   }
   
   @Test
   public void testTransformWithModule() throws Exception {
      GlobalContext globalContext = context.getPoolContext().getGlobalContext();
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
      Action action = new UnwrapSOAPAction(false, true);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(new AssignAction("request", "."));
      Directory modules = new Directory(globalContext.getFileSystem().getRoot(), "modules");
      Directory queries = new Directory(globalContext.getFileSystem().getRoot(), "queries");
		XQueryArtifact module = new XQueryArtifact(modules, "helloworld.xqy");
      module.setContent("module namespace hello = 'http://helloworld'; declare function hello:helloworld() { 'hello world' };".getBytes());
      XQueryArtifact xqueryArtifact = new XQueryArtifact(queries, "test.xqy");
      xqueryArtifact.setContent("import module namespace hw='http://helloworld' at '../modules/helloworld.xqy'; declare variable $request as element() external; (hw:helloworld(), $request)".getBytes());
      xqueryArtifact.validateInternal(globalContext);
      assertTrue(module.isValidated());
      assertTrue(xqueryArtifact.getReferenced().size() > 0);
      assertTrue(xqueryArtifact.getReferenced().contains("/modules/helloworld.xqy"));
      TransformAction nextAction = new TransformAction(xqueryArtifact.getXQuery(), Arrays.asList("greetings"), xqueryArtifact.getParent().getURI());
      action = action.setNextAction(nextAction);
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
      assertEquals("demoElementRequest", message.getVariable(ESBConstants.SOAP_OPERATION));
      assertEquals("hello world", message.getVariable("greetings"));
   }
   
   @Test
   public void testTransformWithModuleFromRoot() throws Exception {
      GlobalContext globalContext = context.getPoolContext().getGlobalContext();
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
      Action action = new UnwrapSOAPAction(false, true);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(new AssignAction("request", "."));
      Directory modules = new Directory(globalContext.getFileSystem().getRoot(), "modules");
		XQueryArtifact module = new XQueryArtifact(modules, "helloworld.xqy");
      module.setContent("module namespace hello = 'http://helloworld'; declare function hello:helloworld() { 'hello world' };".getBytes());
      XQueryArtifact xqueryArtifact = new XQueryArtifact(globalContext.getFileSystem().getRoot(), "test.xqy");
      xqueryArtifact.setContent("import module namespace hw='http://helloworld' at 'modules/helloworld.xqy'; declare variable $request as element() external; (hw:helloworld(), $request)".getBytes());
      xqueryArtifact.validateInternal(globalContext);
      assertTrue(module.isValidated());
      assertTrue(xqueryArtifact.getReferenced().size() > 0);
      assertTrue(xqueryArtifact.getReferenced().contains("/modules/helloworld.xqy"));
      TransformAction nextAction = new TransformAction(xqueryArtifact.getXQuery(), Arrays.asList("greetings"), xqueryArtifact.getParent().getURI());
      action = action.setNextAction(nextAction);
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
      assertEquals("demoElementRequest", message.getVariable(ESBConstants.SOAP_OPERATION));
      assertEquals("hello world", message.getVariable("greetings"));
   }
   
   public void testTransformWithSchema() throws Exception {
   	// Feature not supported in Saxon HE
      GlobalContext globalContext = context.getPoolContext().getGlobalContext();
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
      Action action = new UnwrapSOAPAction(false, true);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(new AssignAction("request", "."));
      XSDArtifact xsdArtifact = new XSDArtifact(globalContext.getFileSystem().getRoot(), "kdf.xsd");
      xsdArtifact.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
      xsdArtifact.validateInternal(null);
      XQueryArtifact xqueryArtifact = new XQueryArtifact(null, null);
      xqueryArtifact.setContent("import schema default element namespace 'http://aoa.de/ei/foundation/v1' at '/kdf.xsd'; declare variable $request as element() external; (validate($request/*[1]/*[1]), $request)".getBytes());
      xqueryArtifact.validateInternal(globalContext);
      TransformAction nextAction = new TransformAction(xqueryArtifact.getXQuery(), Arrays.asList("validate"), xqueryArtifact.getParent().getURI());
      action = action.setNextAction(nextAction);
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
      assertEquals("demoElementRequest", message.getVariable(ESBConstants.SOAP_OPERATION));
   }
   
   public void testJMSConsumer() throws Exception {
      JMSConsumer jmsConsumer = new JMSConsumer(context.getPoolContext().getGlobalContext(), null, null, "ConnectionFactory", "dynamicQueues/test1", null, null, null, 1);
      MarkAction markAction = new MarkAction();
      jmsConsumer.setStartAction(markAction);
      jmsConsumer.init(context.getPoolContext().getGlobalContext());
      
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest1.xml"));
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
      
      JMSAction jmsAction = new JMSAction(context.getPoolContext().getGlobalContext(), "ConnectionFactory", "dynamicQueues/test1", null, null, false, Message.DEFAULT_DELIVERY_MODE, Message.DEFAULT_PRIORITY, 100, false);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(jmsAction);
      consumerPort.process(context, message);
      assertFalse(markAction.executed);

      Thread.sleep(100);
      
      assertTrue(markAction.executed);
      
      jmsConsumer.close();
   }
   
   @Test
   public void testFastinfoset() throws Exception {
      File dir = new File("src/test/resources");
      assertTrue(dir.exists());
      FileSystem fileSystem = new FileSystem();
      fileSystem.parseDirectory(context.getPoolContext().getGlobalContext(), dir).getServiceArtifacts();
      WSDLArtifact wsdlArtifact = fileSystem.getArtifact("/example/example.wsdl");

      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
      message.getHeaders().put(HttpConstants.HTTP_HEADER_SOAP_ACTION, "\"\"");
      Action action = new UnwrapSOAPAction(false, true, wsdlArtifact.getSchema(), wsdlArtifact.getDefinition(), null, null);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      SetMessageAction setMessageAction = new SetMessageAction(false, null, null, null, null);
      setMessageAction.addHeader(HttpConstants.HTTP_HEADER_ACCEPT, HttpConstants.HTTP_HEADER_CONTENT_TYPE_FI_SOAP11, null, null);
		action = action.setNextAction(setMessageAction);
      action = action.setNextAction(new WrapSOAPAction(false, false, true));
      action = action.setNextAction(new DumpAction());
      action = action.setNextAction(new UnwrapSOAPAction(false, true));
      setMessageAction = new SetMessageAction(false, null, null, null, null);
      setMessageAction.addHeader(HttpConstants.HTTP_HEADER_ACCEPT, "", null, null);
		action = action.setNextAction(setMessageAction);
      action = action.setNextAction(new WrapSOAPAction(false, false, true));
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
   }
   
   @Test
   public void testFastinfosetValidate() throws Exception {
      File dir = new File("src/test/resources");
      assertTrue(dir.exists());
      FileSystem fileSystem = new FileSystem();
      fileSystem.parseDirectory(context.getPoolContext().getGlobalContext(), dir).getServiceArtifacts();
      WSDLArtifact wsdlArtifact = fileSystem.getArtifact("/example/example.wsdl");

      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
      message.getHeaders().put(HttpConstants.HTTP_HEADER_SOAP_ACTION, "\"\"");
      message.getHeaders().put(HttpConstants.HTTP_HEADER_ACCEPT, HttpConstants.HTTP_HEADER_CONTENT_TYPE_FI_SOAP11);
      Action action = new ValidateAction(wsdlArtifact.getSchema(), ".", null);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
		List<Entry<String, String>> assignments = java.util.Collections.singletonList(Collections.createEntry(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "'" + HttpConstants.HTTP_HEADER_CONTENT_TYPE_FI_SOAP11 + "'"));
		action = action.setNextAction(new AssignHeadersAction(assignments, null, java.util.Collections.<String>emptyList(), null, true));
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
   }
   
}
