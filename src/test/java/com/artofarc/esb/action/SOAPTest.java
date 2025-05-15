package com.artofarc.esb.action;

import static org.junit.Assert.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.jms.Message;
import javax.jws.WebService;
import javax.jws.soap.SOAPBinding;
import javax.xml.soap.SOAPConstants;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamResult;
import javax.xml.ws.Endpoint;

import org.junit.Test;
import org.w3c.dom.Node;

import com.artofarc.esb.AbstractESBTest;
import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.artifact.Directory;
import com.artofarc.esb.artifact.FileSystem;
import com.artofarc.esb.artifact.SchemaArtifact;
import com.artofarc.esb.artifact.WSDLArtifact;
import com.artofarc.esb.artifact.XMLProcessingArtifact;
import com.artofarc.esb.artifact.XQueryArtifact;
import com.artofarc.esb.artifact.XSDArtifact;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.jms.JMSConnectionData;
import com.artofarc.esb.jms.JMSConsumer;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.service.XQDecl;
import com.artofarc.util.DataStructures;
import com.artofarc.util.TimeGauge;
import com.artofarc.util.WSDL4JUtil;
import com.artofarc.util.XQuerySource;

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
      message.setContentType("text/xml; charset=\"utf-8\"");
      Action action = createUnwrapSOAPAction(false, true);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(new WrapSOAPAction(false, true,true));
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
      @SuppressWarnings("unused")
		Node node = message.getVariable("header");
      System.out.println();
      //context.transform(new DOMSource(node), new StreamResult(System.out));
      node = message.getVariable("messageHeader");
      System.out.println();
      //context.transform(new DOMSource(node), new StreamResult(System.out));
      System.out.println();
   }

   @Test
   public void testHTTPAsync() throws Exception {
      String url = "http://localhost:1212/echo";
      Endpoint endpoint = Endpoint.publish(url, new Echo());
      System.out.println("Service started @ " + url);
      //
      Action action = createAssignAction("request", ".");
      MarkAction errorHandler = new MarkAction();
      action.setErrorHandler(errorHandler);
      @SuppressWarnings("resource")
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      //action = action.setNextAction(new DumpAction(true, null));
      action = action.setNextAction(createHttpAction(url));
      //action = action.setNextAction(createHttpAction("http://localhost:1213/echo"));
      MarkAction markAction = new MarkAction();
      action = action.setNextAction(markAction);
      //
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest2.xml"));
      message.getVariables().put(ESBConstants.HttpMethod, "POST");
      message.getVariables().put(ESBConstants.AsyncContext, true);
      message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.putHeader(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
      consumerPort.process(context, message);
      TimeGauge timeGauge = new TimeGauge(logger, 0, false);
      timeGauge.startTimeMeasurement();
      markAction.isExecuted(5000);
      timeGauge.stopTimeMeasurement("Receiving async answer", false);
      assertFalse(errorHandler.isExecuted());
      endpoint.stop();
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
      message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.putHeader(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
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
      message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.putHeader(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
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
      message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.putHeader(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
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
      message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.putHeader(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
      consumerPort.process(context, message);
      assertTrue(markAction.isExecuted(1500l));
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
      action = action.setNextAction(new PostSOAPHttpAction(false, false, null));
      action = action.setNextAction(new DumpAction());
      //
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest2.xml"));
      message.getVariables().put(ESBConstants.HttpMethod, "POST");
      message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.putHeader("Expect", "100-continue");
      //message.putHeader(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
      try {
         consumerPort.process(context, message);
         fail();
      } catch (ExecutionException e) {
         // expected
         assertTrue(e.getAction() instanceof PostSOAPHttpAction);
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
      action = action.setNextAction(new PostSOAPHttpAction(false, false, null));
      MarkAction errorHandler = new MarkAction();
      action.setErrorHandler(errorHandler);
      action = action.setNextAction(new DumpAction());
      //
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest2.xml"));
      message.getVariables().put(ESBConstants.HttpMethod, "POST");
      message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.putHeader(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
      consumerPort.process(context, message);
      assertTrue(errorHandler.isExecuted());
      endpoint.stop();
   }

   @Test
   public void testValidate() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.setContentType("text/xml; charset=\"utf-8\"");
      Action action = createUnwrapSOAPAction(false, true);
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
   public void testPreventXXE() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequestXXE.xml"));
      message.setContentType("text/xml; charset=\"utf-8\"");
      try {
	      context.transformRaw(message.getBodyAsSource(context), new StreamResult(System.out));
	      fail("XXE Attack not detected");
      } catch (TransformerException e) {
    	  // expected
      }
   }

   @Test
   public void testValidateIsLastAction() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.setContentType("text/xml; charset=\"utf-8\"");
      Action action = createUnwrapSOAPAction(false, true);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(new TransformAction("declare namespace v1=\"http://aoa.de/ei/foundation/v1\"; v1:messageHeader"));
      XSDArtifact xsdArtifact = new XSDArtifact(null, null, "kdf");
      xsdArtifact.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
      xsdArtifact.validateInternal(getGlobalContext());
      action = action.setNextAction(createValidateAction(xsdArtifact));
      consumerPort.process(context, message);
   }

   @Test
   public void testValidateDirect() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.setContentType("text/xml; charset=\"utf-8\"");
      Action action = createUnwrapSOAPAction(false, true);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      XSDArtifact xsdArtifact = new XSDArtifact(null, null, "kdf");
      xsdArtifact.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
      xsdArtifact.validateInternal(getGlobalContext());
      List<Map.Entry<String, String>> result = new ArrayList<>();
      result.add(DataStructures.createEntry("v1", "http://aoa.de/ei/foundation/v1"));
      action = action.setNextAction(new ValidateAction(xsdArtifact.getSchema(), "v1:messageHeader", result, null));
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
   }
   
   @Test
   public void testTransform() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.setContentType("text/xml; charset=\"utf-8\"");
      Action action = createUnwrapSOAPAction(false, true);
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
	public void testTransformEmpty() throws Exception {
		ESBMessage message = new ESBMessage(BodyType.INVALID, null);
		ConsumerPort consumerPort = new ConsumerPort(null);
		String xquery = "<dummy/>";
		TransformAction action = new TransformAction(xquery);
		action.setNextAction(new DumpAction());
		consumerPort.setStartAction(action);
		consumerPort.process(context, message);
		assertTrue(message.getBodyAsString(context).startsWith(xquery));
	}
   
   @Test
   public void testTransformWithStaticData() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.setContentType("text/xml; charset=\"utf-8\"");
      Action action = createUnwrapSOAPAction(false, true);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(createAssignAction("request", "."));
      Directory root = getGlobalContext().getFileSystem().getRoot();
      Directory queries = new Directory(null, root, "queries");
      Directory staticData = new Directory(null, root, "data");
		XQueryArtifact xqueryArtifact = new XQueryArtifact(null, queries, null);
      String strContent = "declare variable $request as element() external; (doc('/data/static.xml')/*[1]/text())";
		xqueryArtifact.setContent(strContent.getBytes());
		XMLProcessingArtifact staticXML = new XMLProcessingArtifact(null, staticData, "static.xml");
		staticXML.setContent("<root>Hello World!</root>".getBytes());
      xqueryArtifact.validateInternal(getGlobalContext());
      staticXML.setModificationTime(1);
      getGlobalContext().getFileSystem().dehydrateArtifacts(staticData);
      TransformAction nextAction = new TransformAction(strContent);
      action = action.setNextAction(nextAction);
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
   }
   
   @Test
   public void testTransformWithModule() throws Exception {
      GlobalContext globalContext = getGlobalContext();
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.setContentType("text/xml; charset=\"utf-8\"");
      Action action = createUnwrapSOAPAction(false, true);
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
      module.setModificationTime(1);
      getGlobalContext().getFileSystem().dehydrateArtifacts(modules);
      TransformAction nextAction = createTransformAction(xqueryArtifact, "greetings");
      action = action.setNextAction(nextAction);
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
      assertEquals("hello world", message.getVariable("greetings"));
   }
   
   @Test
   public void testTransformWithModuleFromRoot() throws Exception {
      GlobalContext globalContext = getGlobalContext();
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.setContentType("text/xml; charset=\"utf-8\"");
      Action action = createUnwrapSOAPAction(false, true);
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
      TransformAction nextAction = createTransformAction(xqueryArtifact, "greetings");
      action = action.setNextAction(nextAction);
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
      assertEquals("hello world", message.getVariable("greetings"));
   }
   
   public void testTransformWithSchema() throws Exception {
   	// Feature not supported in Saxon HE
      GlobalContext globalContext = getGlobalContext();
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
      Action action = createUnwrapSOAPAction(false, true);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(createAssignAction("request", "."));
      XSDArtifact xsdArtifact = new XSDArtifact(null, globalContext.getFileSystem().getRoot(), "kdf.xsd");
      xsdArtifact.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
      xsdArtifact.validateInternal(null);
      XQueryArtifact xqueryArtifact = new XQueryArtifact(null, null, null);
      xqueryArtifact.setContent("import schema default element namespace 'http://aoa.de/ei/foundation/v1' at '/kdf.xsd'; declare variable $request as element() external; (validate($request/*[1]/*[1]), $request)".getBytes());
      xqueryArtifact.validateInternal(globalContext);
      TransformAction nextAction = createTransformAction(xqueryArtifact, "validate");
      action = action.setNextAction(nextAction);
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
      assertEquals("demoElementRequest", message.getVariable(ESBConstants.SOAP_OPERATION));
   }
   
   @Test
   public void testJMSConsumer() throws Exception {
//	   org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ embeddedActiveMQ = new org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ();
//	   embeddedActiveMQ.start();
	   
	   // java:/ConnectionFactory
	   List<JMSConnectionData> jmsConnectionData = JMSConnectionData.create(getGlobalContext(), "ConnectionFactory", null, null, null);
      JMSConsumer jmsConsumer = new JMSConsumer(getGlobalContext(), null, null, jmsConnectionData.get(0), "dynamicQueues/test1", null, null, null, null, null, false, false, null, 1, 1, 2, 1000, 0L, "milliseconds", null);
      MarkAction markAction = new MarkAction();
      jmsConsumer.setStartAction(markAction);
      jmsConsumer.init(getGlobalContext());
      
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      
      JMSAction jmsAction = new JMSAction(getGlobalContext(), jmsConnectionData, false, false, "dynamicQueues/test1", null, null, null, false, Message.DEFAULT_DELIVERY_MODE, "" + Message.DEFAULT_PRIORITY, 10000L, null, null, false, null, null, null, null, null);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(jmsAction);
      assertFalse(markAction.isExecuted());
      consumerPort.process(context, message);

      assertTrue(markAction.isExecuted(100));
      
      jmsConsumer.close();
//      embeddedActiveMQ.stop();
   }
   
   @Test
   public void testFastinfoset() throws Exception {
      closeContext();
      createContext("src/test/resources");
      FileSystem fileSystem = getGlobalContext().getFileSystem();
      fileSystem.init(getGlobalContext()).getServiceArtifacts();
      WSDLArtifact wsdlArtifact = fileSystem.getArtifact("/example/example.wsdl");

      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.putVariable(ESBConstants.HttpMethod, "POST");
      message.setContentType("text/xml; charset=\"utf-8\"");
      message.putHeader(HttpConstants.HTTP_HEADER_SOAP_ACTION, "\"\"");
      
      @SuppressWarnings("unchecked")
      Action action = new UnwrapSOAPAction(false, true, wsdlArtifact.getSchema(), WSDL4JUtil.getBinding(wsdlArtifact.getAllBindings(), null, null).getBindingOperations());
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(new WrapSOAPAction(false, false, true));
      SetMessageAction setMessageAction = createUpdateAction(null, null, null);
      setMessageAction.addAssignment(HttpConstants.HTTP_HEADER_CONTENT_TYPE, true, HttpConstants.HTTP_HEADER_CONTENT_TYPE_FI_SOAP11, null, null, null);
      action = action.setNextAction(setMessageAction);
      action = action.setNextAction(new MarkAction());
      action = action.setNextAction(new DumpAction());
      action = action.setNextAction(createUnwrapSOAPAction(false, true));
      action = action.setNextAction(new WrapSOAPAction(false, false, true));
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
   }
   
   @Test
   public void testFastinfosetBlob() throws Exception {
      closeContext();
      createContext("src/test/resources");
      FileSystem fileSystem = getGlobalContext().getFileSystem();
      fileSystem.init(getGlobalContext()).getServiceArtifacts();
      SchemaArtifact schemaArtifact = fileSystem.getArtifact("/example/de.aoa.ei.lms.v1.xsd");

      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/de.aoa.ei.lms.v1.xml"));
      message.setContentType("text/xml; charset=\"utf-8\"");
      message.putHeader(HttpConstants.HTTP_HEADER_SOAP_ACTION, "\"\"");
      message.addAttachment("11", "application/pdf", "11".getBytes(), "11");

      message.setSchema(schemaArtifact.getSchema());
      
      @SuppressWarnings("unchecked")
      ConsumerPort consumerPort = new ConsumerPort(null);
      SetMessageAction setMessageAction = createUpdateAction(null, null, null);
      setMessageAction.addAssignment(HttpConstants.HTTP_HEADER_CONTENT_TYPE, true, HttpConstants.HTTP_HEADER_CONTENT_TYPE_FI_SOAP11, null, null, null);
      setMessageAction.setNextAction(new XOPDeserializeAction());
      consumerPort.setStartAction(setMessageAction);
//	Action action = setMessageAction.setNextAction(createValidateAction(schemaArtifact));
//	 action = setMessageAction.setNextAction(new DumpAction());
      consumerPort.process(context, message);
      message.writeTo(System.out, context);
   }
   
   @Test
   public void testXOPSerialize() throws Exception {
      closeContext();
      createContext("src/test/resources");
      FileSystem fileSystem = getGlobalContext().getFileSystem();
      fileSystem.init(getGlobalContext()).getServiceArtifacts();
      SchemaArtifact schemaArtifact = fileSystem.getArtifact("/example/de.aoa.ei.lms.v1.xsd");

      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/de.aoa.ei.lms.v1.xml"));
      message.setContentType("text/xml; charset=\"utf-8\"");
      message.putHeader(HttpConstants.HTTP_HEADER_SOAP_ACTION, "\"\"");
      message.setSchema(schemaArtifact.getSchema());
      message.addAttachment("11", "application/pdf", "11".getBytes(), "11");
      
      @SuppressWarnings("unchecked")
      ConsumerPort consumerPort = new ConsumerPort(null);
	consumerPort.setStartAction(
//			new TransformAction("."),
			new XOPSerializeAction(0, "application/octet-stream", null)
			,new XOPDeserializeAction()
//			,new TransformAction("declare function local:copy-xop($element as element(), $attachments as element()) as element() {\n" +
//					"element {node-name($element)}\n" +
//					"{ $element/@*,\n" +
//					"for $child in $element/node()\n" +
//						"return if ($child instance of element())\n" +
//							"then if ($child/local-name() = 'Include' and $child/namespace-uri() = 'http://www.w3.org/2004/08/xop/include')\n" +
//								"then $attachments/*[@cid=$child/@href]/text()\n" +
//								"else local:copy-xop($child, $attachments)\n" +
//							"else $child\n" +
//					"}\n" +
//			"};\n" +
//			"declare variable $attachments as document-node() external;" +
//			"local:copy-xop(if (. instance of element()) then . else *, $attachments/*)")
//			,new TerminalAction() {}
			,new DumpAction()
			);
      consumerPort.process(context, message);
	
//		TimeGauge timeGauge = new TimeGauge(Action.logger, 0L, false);
//		timeGauge.startTimeMeasurement();
//		int count = 100000;
//		for (int i = 0; i < count; ++i) {
//			consumerPort.process(context, message);
//		}
//		long measurement = timeGauge.stopTimeMeasurement("Performance", false);
//		System.out.println("Average in µs: " + measurement * 1000 / count);
   }
   
   @Test
   public void testFastinfosetValidate() throws Exception {
      closeContext();
      createContext("src/test/resources");
      FileSystem fileSystem = getGlobalContext().getFileSystem();
      fileSystem.init(getGlobalContext()).getServiceArtifacts();
      WSDLArtifact wsdlArtifact = fileSystem.getArtifact("/example/example.wsdl");

      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, "text/xml; charset=\"utf-8\"");
      message.putHeader(HttpConstants.HTTP_HEADER_SOAP_ACTION, "\"\"");
      message.putHeader(HttpConstants.HTTP_HEADER_ACCEPT, HttpConstants.HTTP_HEADER_CONTENT_TYPE_FI_SOAP11);
      Action action = createValidateAction(wsdlArtifact);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(new AssignAction(createAssignments(true, HttpConstants.HTTP_HEADER_CONTENT_TYPE, "'" + HttpConstants.HTTP_HEADER_CONTENT_TYPE_FI_SOAP11 + "'"), null, null, java.util.Collections.<XQDecl> emptyList(), null, null, true));
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
   }
   
   @Test
   public void testXqueryReturnsJSON() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.INVALID, null);
      ConsumerPort consumerPort = new ConsumerPort(null);
      Action action = new AssignAction(Collections.emptyList(), "'{}'", null, java.util.Collections.<XQDecl> emptyList(), null, null, false);
      consumerPort.setStartAction(action);
      action = action.setNextAction(new Json2XMLAction(null, null, false, "root", null, null));
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
   }
   
  @Test
   public void testInsertReplyContext() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest.xml"));
      message.setContentType("text/xml; charset=\"utf-8\"");
      Action action = createUnwrapSOAPAction(false, true);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
		GlobalContext globalContext = getGlobalContext();
		XQueryArtifact module = new XQueryArtifact(globalContext.getFileSystem(), globalContext.getFileSystem().getRoot(), "service-utils.xqm");
		module.setContent(readFile("src/test/resources/service-utils.xqm"));

      action = action.setNextAction(new TransformAction(XQuerySource.create("import module namespace fn-svi='http://aoa.de/esb/service-utils' at '/service-utils.xqm'; fn-svi:copyAndInsertReplyContext(., 'SGFsbG8gV2VsdCE=')"), "/", null, false, null));
      action = action.setNextAction(new TransformAction("declare namespace v1=\"http://aoa.de/ei/foundation/v1\"; ./*[1]"));
      XSDArtifact xsdArtifact = new XSDArtifact(null, null, "kdf");
      xsdArtifact.setContent(readFile("src/test/resources/example/de.aoa.ei.foundation.v1.xsd"));
      xsdArtifact.validateInternal(globalContext);
      action = action.setNextAction(createValidateAction(xsdArtifact));
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
   }
   
}
