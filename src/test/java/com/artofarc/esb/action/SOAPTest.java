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
import javax.wsdl.BindingOperation;
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
import com.artofarc.esb.context.XQuerySource;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.jms.JMSConsumer;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.util.Collections;
import com.artofarc.util.WSDL4JUtil;


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
      Action action = createAssignAction("request", ".");
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(createHttpOutboundAction((url)));
      action = action.setNextAction(new HttpInboundAction());
      action = action.setNextAction(createAssignAction("response", "."));
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
      Action action = createAssignAction("request", ".");
      ConsumerPort consumerPort = new ConsumerPort(null);
      MarkAction markAction = new MarkAction();
		context.getExecutionStack().push(markAction);
      consumerPort.setStartAction(action);
      action = action.setNextAction(createHttpOutboundAction(url));
      action = action.setNextAction(new SpawnAction(null, false, false));
      action = action.setNextAction(new HttpInboundAction());
      action = action.setNextAction(createAssignAction("response", "."));
      //
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest2.xml"));
      message.getVariables().put(ESBConstants.HttpMethod, "POST");
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.getHeaders().put(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
      consumerPort.process(context, message);
      assertTrue(markAction.isExecuted(1000l));
      endpoint.stop();
   }

   @Test
   public void testHTTPDecoupled() throws Exception {
      String url = "http://localhost:1212/echo";
      Endpoint endpoint = Endpoint.publish(url, new Echo());
      System.out.println("Service started @ " + url);
      //
      Action action = createAssignAction("request", ".");
      ConsumerPort consumerPort = new ConsumerPort(null);
      MarkAction markAction = new MarkAction();
		context.getExecutionStack().push(markAction);
      consumerPort.setStartAction(action);
      action = action.setNextAction(new SpawnAction(null, false, false));
      //action = action.setNextAction(createAssignAction("request", "."));
      action = action.setNextAction(createHttpOutboundAction(url));
      action = action.setNextAction(new HttpInboundAction());
      //action = action.setNextAction(createAssignAction("response", "."));
      //
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest2.xml"));
      message.getVariables().put(ESBConstants.HttpMethod, "POST");
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.getHeaders().put(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
      consumerPort.process(context, message);
      assertTrue(markAction.isExecuted(1000l));
      endpoint.stop();
   }

   @Test
   public void testHTTPDecoupledUsingPipe() throws Exception {
      String url = "http://localhost:1212/echo";
      Endpoint endpoint = Endpoint.publish(url, new Echo());
      System.out.println("Service started @ " + url);
      //
      Action action = createAssignAction("request", ".");
      ConsumerPort consumerPort = new ConsumerPort(null);
      MarkAction markAction = new MarkAction();
		context.getExecutionStack().push(markAction);
      consumerPort.setStartAction(action);
      action = action.setNextAction(new SpawnAction(null, true, false));
      //action = action.setNextAction(createAssignAction("request", "."));
      action = action.setNextAction(createHttpOutboundAction(url));
      action = action.setNextAction(new HttpInboundAction());
      //action = action.setNextAction(createAssignAction("response", "."));
      //action = action.setNextAction(new DumpAction());
      //
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest2.xml"));
      message.getVariables().put(ESBConstants.HttpMethod, "POST");
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.getHeaders().put(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
      consumerPort.process(context, message);
      assertTrue(markAction.isExecuted(1000l));
      endpoint.stop();
   }

   @Test
   public void testHTTPFault() throws Exception {
      String url = "http://localhost:1212/echo";
      Endpoint endpoint = Endpoint.publish(url, new Echo());
      System.out.println("Service started @ " + url);
      //
      Action action = createHttpOutboundAction("http://localhost:1212/e");
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
      Action action = createHttpOutboundAction("http://localhost:1212/e");
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
      assertTrue(errorHandler.isExecuted());
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
      XSDArtifact xsdArtifact = new XSDArtifact(null, null, "kdf");
      xsdArtifact.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
      xsdArtifact.validateInternal(getGlobalContext());
      action = action.setNextAction(createValidateAction(xsdArtifact));
      action = action.setNextAction(createValidateAction(xsdArtifact));
      action = action.setNextAction(createValidateAction(xsdArtifact));
      action = action.setNextAction(createValidateAction(xsdArtifact));
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
   }
   
   @Test
   public void testValidateDirect() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
      Action action = new UnwrapSOAPAction(false, true);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      XSDArtifact xsdArtifact = new XSDArtifact(null, null, "kdf");
      xsdArtifact.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
      xsdArtifact.validateInternal(getGlobalContext());
      List<Entry<String, String>> result = new ArrayList<>();
      result.add(Collections.createEntry("v1", "http://aoa.de/ei/foundation/v1"));
      action = action.setNextAction(new ValidateAction(xsdArtifact.getSchema(), "v1:messageHeader", result, null));
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
   }
   
   @Test
   public void testTransform() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
      Action action = new UnwrapSOAPAction(false, true);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(createAssignAction("request", "."));
      XQueryArtifact xqueryArtifact = new XQueryArtifact(null, getGlobalContext().getFileSystem().getRoot(), null);
      String strContent = "declare variable $request as element() external; $request";
		xqueryArtifact.setContent(strContent.getBytes());
      xqueryArtifact.validateInternal(getGlobalContext());
      TransformAction nextAction = new TransformAction(strContent);
      action = action.setNextAction(nextAction);
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
   }
   
   @Test
   public void testTransformWithStaticData() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
      Action action = new UnwrapSOAPAction(false, true);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(createAssignAction("request", "."));
      Directory root = getGlobalContext().getFileSystem().getRoot();
      Directory queries = new Directory(null, root, "queries");
      Directory staticData = new Directory(null, root, "data");
		XQueryArtifact xqueryArtifact = new XQueryArtifact(null, queries, null);
      String strContent = "declare variable $request as element() external; (doc('/data/static.xml')/*[1]/text(), $request)";
		xqueryArtifact.setContent(strContent.getBytes());
		XMLArtifact staticXML = new XMLArtifact(null, staticData, "static.xml");
		staticXML.setContent("<root>Hello World!</root>".getBytes());
      xqueryArtifact.validateInternal(getGlobalContext());
      TransformAction nextAction = new TransformAction(strContent);
      action = action.setNextAction(nextAction);
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
   }
   
   @Test
   public void testTransformWithModule() throws Exception {
      GlobalContext globalContext = getGlobalContext();
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
      Action action = new UnwrapSOAPAction(false, true);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(createAssignAction("request", "."));
      Directory modules = new Directory(globalContext.getFileSystem(), globalContext.getFileSystem().getRoot(), "modules");
      Directory queries = new Directory(globalContext.getFileSystem(), globalContext.getFileSystem().getRoot(), "queries");
		XQueryArtifact module = new XQueryArtifact(globalContext.getFileSystem(), modules, "helloworld.xqy");
      module.setContent("module namespace hello = 'http://helloworld'; declare function hello:helloworld() { 'hello world' };".getBytes());
      XQueryArtifact xqueryArtifact = new XQueryArtifact(globalContext.getFileSystem(), queries, "test.xqy");
      xqueryArtifact.setContent("import module namespace hw='http://helloworld' at '../modules/helloworld.xqy'; declare variable $request as element() external; (hw:helloworld(), $request)".getBytes());
      xqueryArtifact.validateInternal(globalContext);
      assertTrue(module.isValidated());
      assertTrue(xqueryArtifact.getReferenced().size() > 0);
      assertTrue(xqueryArtifact.getReferenced().contains("/modules/helloworld.xqy"));
      TransformAction nextAction = new TransformAction(new XQuerySource(xqueryArtifact.getContent()), Arrays.asList("greetings"), xqueryArtifact.getParent().getURI());
      action = action.setNextAction(nextAction);
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
      assertEquals("hello world", message.getVariable("greetings"));
   }
   
   @Test
   public void testTransformWithModuleFromRoot() throws Exception {
      GlobalContext globalContext = getGlobalContext();
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
      Action action = new UnwrapSOAPAction(false, true);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(createAssignAction("request", "."));
      Directory modules = new Directory(globalContext.getFileSystem(), globalContext.getFileSystem().getRoot(), "modules");
		XQueryArtifact module = new XQueryArtifact(globalContext.getFileSystem(), modules, "helloworld.xqy");
      module.setContent("module namespace hello = 'http://helloworld'; declare function hello:helloworld() { 'hello world' };".getBytes());
      XQueryArtifact xqueryArtifact = new XQueryArtifact(globalContext.getFileSystem(), globalContext.getFileSystem().getRoot(), "test.xqy");
      xqueryArtifact.setContent("import module namespace hw='http://helloworld' at '/modules/helloworld.xqy'; declare variable $request as element() external; (hw:helloworld(), $request)".getBytes());
      xqueryArtifact.validateInternal(globalContext);
      assertTrue(module.isValidated());
      assertTrue(xqueryArtifact.getReferenced().size() > 0);
      assertTrue(xqueryArtifact.getReferenced().contains("/modules/helloworld.xqy"));
      TransformAction nextAction = new TransformAction(new XQuerySource(xqueryArtifact.getContent()), Arrays.asList("greetings"), xqueryArtifact.getParent().getURI());
      action = action.setNextAction(nextAction);
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
      assertEquals("hello world", message.getVariable("greetings"));
   }
   
   public void testTransformWithSchema() throws Exception {
   	// Feature not supported in Saxon HE
      GlobalContext globalContext = getGlobalContext();
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
      Action action = new UnwrapSOAPAction(false, true);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(createAssignAction("request", "."));
      XSDArtifact xsdArtifact = new XSDArtifact(null, globalContext.getFileSystem().getRoot(), "kdf.xsd");
      xsdArtifact.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
      xsdArtifact.validateInternal(null);
      XQueryArtifact xqueryArtifact = new XQueryArtifact(null, null, null);
      xqueryArtifact.setContent("import schema default element namespace 'http://aoa.de/ei/foundation/v1' at '/kdf.xsd'; declare variable $request as element() external; (validate($request/*[1]/*[1]), $request)".getBytes());
      xqueryArtifact.validateInternal(globalContext);
      TransformAction nextAction = new TransformAction(new XQuerySource(xqueryArtifact.getContent()), Arrays.asList("validate"), xqueryArtifact.getParent().getURI());
      action = action.setNextAction(nextAction);
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
      assertEquals("demoElementRequest", message.getVariable(ESBConstants.SOAP_OPERATION));
   }
   
   public void testJMSConsumer() throws Exception {
      JMSConsumer jmsConsumer = new JMSConsumer(getGlobalContext(), null, null, "ConnectionFactory", "dynamicQueues/test1", null, null, null, null, 1, 0L);
      MarkAction markAction = new MarkAction();
      jmsConsumer.setStartAction(markAction);
      jmsConsumer.init(getGlobalContext());
      
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest1.xml"));
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
      
      JMSAction jmsAction = new JMSAction(getGlobalContext(), "ConnectionFactory", "dynamicQueues/test1", null, null, false, Message.DEFAULT_DELIVERY_MODE, Message.DEFAULT_PRIORITY, 100, false);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(jmsAction);
      consumerPort.process(context, message);
      assertFalse(markAction.isExecuted());

      Thread.sleep(100);
      
      assertTrue(markAction.isExecuted());
      
      jmsConsumer.close();
   }
   
   @Test
   public void testFastinfoset() throws Exception {
      File dir = new File("src/test/resources");
      assertTrue(dir.exists());
      createContext(dir);
      FileSystem fileSystem = getGlobalContext().getFileSystem();
      fileSystem.init(getGlobalContext()).getServiceArtifacts();
      WSDLArtifact wsdlArtifact = fileSystem.getArtifact("/example/example.wsdl");

      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
      message.getHeaders().put(HttpConstants.HTTP_HEADER_SOAP_ACTION, "\"\"");
      List<BindingOperation> bindingOperations = WSDL4JUtil.getBindingOperations(wsdlArtifact.getAllBindings(), null, null);
      Action action = new UnwrapSOAPAction(false, true, wsdlArtifact.getSchema(), bindingOperations, null, false);
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
      createContext(dir);
      FileSystem fileSystem = getGlobalContext().getFileSystem();
      fileSystem.init(getGlobalContext()).getServiceArtifacts();
      WSDLArtifact wsdlArtifact = fileSystem.getArtifact("/example/example.wsdl");

      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.getHeaders().put(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
      message.getHeaders().put(HttpConstants.HTTP_HEADER_SOAP_ACTION, "\"\"");
      message.getHeaders().put(HttpConstants.HTTP_HEADER_ACCEPT, HttpConstants.HTTP_HEADER_CONTENT_TYPE_FI_SOAP11);
      Action action = createValidateAction(wsdlArtifact);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
		List<Entry<String, String>> assignments = java.util.Collections.singletonList(Collections.createEntry(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "'" + HttpConstants.HTTP_HEADER_CONTENT_TYPE_FI_SOAP11 + "'"));
		action = action.setNextAction(new AssignHeadersAction(assignments, null, java.util.Collections.<String>emptyList(), null, true));
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
   }
   
}
