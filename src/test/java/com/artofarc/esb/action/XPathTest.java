package com.artofarc.esb.action;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.LinkedHashMap;

import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Node;

import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;


public class XPathTest {
   
   private Context context;
   
   @Before
   public void createContext() throws Exception {
      context = new Context(new GlobalContext(null).getDefaultWorkerPool().getPoolContext());
   }

   @After
   public void closeContext() throws Exception {
      if (context != null) {
         context.close();
      }
   }

   @Test
   public void testXQuery() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.STRING, "<test>Hello</test>");
      Action action = new AssignAction("result", "test/text()");
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
      assertEquals("Hello",  message.getVariable("result"));
   }

   @Test
   public void testXQueryNS() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.STRING, "<test>Hello</test>");
      LinkedHashMap<String, String> map = new LinkedHashMap<>();
      map.put("result", "<v1:result>{test/text()}</v1:result>");
      map.put("request", ".");
      LinkedHashMap<String, String> ns = new LinkedHashMap<>();
      ns.put("v1", "http://com.artofarc/v1");
      Action action = new AssignAction(map.entrySet(), ns.entrySet(), Collections.<String>emptyList(), null);
      action.setNextAction(new DumpAction());
      action.process(context, message);
      Node node = message.getVariable("result");
      context.getIdenticalTransformer().transform(new DOMSource(node), new StreamResult(System.out));
      Node request = message.getVariable("request");
      assertTrue(request.getNodeType() == Node.DOCUMENT_NODE);
   }

   @Test
   public void testCondition() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.STRING, "<test>Hello</test>");
      ConditionalAction action = new ConditionalAction("test/text() = \'Hello\'", null, Collections.<String> emptyList(), null);
      MarkAction action2 = new MarkAction();
      MarkAction action3 = new MarkAction();
      action.setNextAction(action2);
      action.setConditionalAction(action3);
      action.process(context, message);
      assertTrue(action3.executed);
      message = new ESBMessage(BodyType.STRING, "<test>Hello World!</test>");
      action.process(context, message);
      assertTrue(action2.executed);
   }
   
   @Test
   public void testJavaExtension() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.STRING, "<test>Hello</test>");
      LinkedHashMap<String, String> map = new LinkedHashMap<>();
      map.put("result", "<result>{fn-artofarc:uuid()}</result>");
      LinkedHashMap<String, String> ns = new LinkedHashMap<>();
      ns.put("fn-artofarc", "http://artofarc.com/xpath-extension");
      Action action = new AssignAction(map.entrySet(), ns.entrySet(), Collections.<String>emptyList(), null);
      action.setNextAction(new DumpAction());
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      consumerPort.process(context, message);
      Node node = message.getVariable("result");
      if (node.getNodeType() == Node.TEXT_NODE) {
         String result = node.getTextContent();
         assertEquals("Hello", result);
      } else {
         context.getIdenticalTransformer().transform(new DOMSource(node), new StreamResult(System.out));
      }
   }

}
