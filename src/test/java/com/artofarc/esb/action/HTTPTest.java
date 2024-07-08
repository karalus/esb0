package com.artofarc.esb.action;

import static org.junit.Assert.*;

import jakarta.jws.WebService;
import jakarta.jws.soap.SOAPBinding;
import jakarta.xml.soap.SOAPConstants;
import jakarta.xml.ws.Endpoint;

import org.junit.Test;

import com.artofarc.esb.AbstractESBTest;
import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.TimeGauge;

public class HTTPTest extends AbstractESBTest {
   
   @WebService
   @SOAPBinding(style=SOAPBinding.Style.DOCUMENT)
   public static class Echo {
      public String checkAlive(String text) throws Exception {
         Thread.sleep(500L);
         return text;
      }
   }   

   @Test
   public void testHTTP2Async() throws Exception {
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
      action = action.setNextAction(new DumpAction(true, null));
      action = action.setNextAction(createHttpAction(url));
      //action = action.setNextAction(createHttpAction("http://localhost:1213/echo"));
      MarkAction markAction = new MarkAction();
      action = action.setNextAction(markAction);
      //
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest2.xml"));
      message.getVariables().put(ESBConstants.HttpMethod, "POST");
      context.putResource(ESBConstants.AsyncContext, this);
      message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.putHeader(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
      consumerPort.process(context, message);
      TimeGauge timeGauge = new TimeGauge(logger, 0, false);
      timeGauge.startTimeMeasurement();
      assertTrue(markAction.isExecuted(5000));
      timeGauge.stopTimeMeasurement("Receiving async answer", false);
      assertFalse(errorHandler.isExecuted());
      endpoint.stop();
   }

   @Test
   public void testHTTP2Sync() throws Exception {
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
      action = action.setNextAction(new DumpAction(true, null));
      action = action.setNextAction(createHttpAction(url));
      //action = action.setNextAction(createHttpAction("http://localhost:1213/echo"));
      MarkAction markAction = new MarkAction();
      action = action.setNextAction(markAction);
      //
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest2.xml"));
      message.getVariables().put(ESBConstants.HttpMethod, "POST");
      //message.getVariables().put(ESBConstants.AsyncContext, true);
      message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.putHeader(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
      consumerPort.process(context, message);
      TimeGauge timeGauge = new TimeGauge(logger, 0, false);
      timeGauge.startTimeMeasurement();
      assertTrue(markAction.isExecuted(5000));
      timeGauge.stopTimeMeasurement("Receiving async answer", false);
      assertFalse(errorHandler.isExecuted());
      endpoint.stop();
   }

   @Test
   public void testHTTP2AsyncStreaming() throws Exception {
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
      context.putResource(ESBConstants.AsyncContext, this);
      message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.putHeader(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
      consumerPort.process(context, message);
      TimeGauge timeGauge = new TimeGauge(logger, 0, false);
      timeGauge.startTimeMeasurement();
      assertTrue(markAction.isExecuted(5000));
      timeGauge.stopTimeMeasurement("Receiving async answer", false);
      assertFalse(errorHandler.isExecuted());
      endpoint.stop();
   }

   @Test
   public void testHTTP2AsyncStreamingError() throws Exception {
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
      //action = action.setNextAction(createHttpAction(url));
      action = action.setNextAction(createHttpAction("http://localhost:1213/echo"));
      MarkAction markAction = new MarkAction();
      action = action.setNextAction(markAction);
      //
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest2.xml"));
      message.getVariables().put(ESBConstants.HttpMethod, "POST");
      context.putResource(ESBConstants.AsyncContext, this);
      message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.putHeader(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
      consumerPort.process(context, message);
      TimeGauge timeGauge = new TimeGauge(logger, 0, false);
      timeGauge.startTimeMeasurement();
      //assertTrue(markAction.isExecuted(50000));
      assertTrue(errorHandler.isExecuted(5000));
      timeGauge.stopTimeMeasurement("Receiving async answer", false);
      endpoint.stop();
   }

   @Test
   public void testHTTP2SyncStreaming() throws Exception {
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
      //message.getVariables().put(ESBConstants.AsyncContext, true);
      message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.putHeader(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
      consumerPort.process(context, message);
      TimeGauge timeGauge = new TimeGauge(logger, 0, false);
      timeGauge.startTimeMeasurement();
      assertTrue(markAction.isExecuted(5000));
      timeGauge.stopTimeMeasurement("Receiving async answer", false);
      assertFalse(errorHandler.isExecuted());
      endpoint.stop();
   }

   @Test
   public void testHTTP2SyncRetry() throws Exception {
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
      action = action.setNextAction(new DumpAction(true, null));
      action = action.setNextAction(createHttpAction("http://localhost:1213/echo", url));
      //action = action.setNextAction(createHttpAction("http://localhost:1213/echo"));
      MarkAction markAction = new MarkAction();
      action = action.setNextAction(markAction);
      //
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest2.xml"));
      message.getVariables().put(ESBConstants.HttpMethod, "POST");
      //message.getVariables().put(ESBConstants.AsyncContext, true);
      message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.putHeader(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
      consumerPort.process(context, message);
      assertFalse(errorHandler.isExecuted());
      TimeGauge timeGauge = new TimeGauge(logger, 0, false);
      timeGauge.startTimeMeasurement();
      assertTrue(markAction.isExecuted(5000));
      timeGauge.stopTimeMeasurement("Receiving async answer", false);
      assertFalse(errorHandler.isExecuted());
      endpoint.stop();
   }

   @Test
   public void testHTTP2AsyncRetry() throws Exception {
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
      action = action.setNextAction(new DumpAction(true, null));
      action = action.setNextAction(createHttpAction("http://localhost:1213/echo", url));
      //action = action.setNextAction(createHttpAction("http://localhost:1213/echo"));
      MarkAction markAction = new MarkAction();
      action = action.setNextAction(markAction);
      //
      ESBMessage message = new ESBMessage(BodyType.BYTES, readFile("src/test/resources/SOAPRequest2.xml"));
      message.getVariables().put(ESBConstants.HttpMethod, "POST");
      context.putResource(ESBConstants.AsyncContext, this);
      message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, SOAPConstants.SOAP_1_1_CONTENT_TYPE);
      //message.putHeader(HttpAction.HTTP_HEADER_SOAP_ACTION, "\"\"");
      consumerPort.process(context, message);
      Thread.sleep(1000);
      assertFalse(errorHandler.isExecuted());
      TimeGauge timeGauge = new TimeGauge(logger, 0, false);
      timeGauge.startTimeMeasurement();
      assertTrue(markAction.isExecuted(50000));
      timeGauge.stopTimeMeasurement("Receiving async answer", false);
      assertFalse(errorHandler.isExecuted());
      endpoint.stop();
   }

}
