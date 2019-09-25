package com.artofarc.esb.action;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.junit.Test;
import org.w3c.dom.Node;

import com.artofarc.esb.AbstractESBTest;
import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.artifact.Directory;
import com.artofarc.esb.artifact.XQueryArtifact;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;


public class XPathTest extends AbstractESBTest {
   
   @Test
   public void testXQuery() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.STRING, "<test>Hello</test>");
      Action action = createAssignAction("result", "test/text()");
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
      assertEquals("Hello",  message.getVariable("result"));
   }

   @Test
   public void testXQueryWithExternalVariable() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.INVALID, null);
      message.putVariable("inVar", "myValue");
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(createAssignAction("result", "$inVar", "inVar"));
      createAssignAction("result", "$inVar", "inVar").setNextAction(new DumpAction());
      consumerPort.process(context, message);
      assertEquals("myValue", message.getVariable("result"));
   }

   @Test
   public void testXQueryNS() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.STRING, "<test>Hello</test>");
      Map<Entry<String, Boolean>, String> assignments = createAssignments("result", "<v1:result>{test/text()}</v1:result>", "request", ".");
      LinkedHashMap<String, String> ns = new LinkedHashMap<>();
      ns.put("v1", "http://com.artofarc/v1");
      Action action = createAssignAction(assignments, ns);
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
      ConditionalAction action = new ConditionalAction("test/text() = \'Hello\'", null, ConditionalAction.emptyNames(), null);
      MarkAction action2 = new MarkAction();
      MarkAction action3 = new MarkAction();
      action.setNextAction(action2);
      action.setConditionalAction(action3);
      action.process(context, message);
      assertTrue(action3.isExecuted());
      message = new ESBMessage(BodyType.STRING, "<test>Hello World!</test>");
      action.process(context, message);
      assertTrue(action2.isExecuted());
   }
   
   @Test
   public void testJavaExtension() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.STRING, "<test>Hello</test>");
      Map<Entry<String, Boolean>, String> assignments = createAssignments("result", "<result>{fn-bea:uuid(), fn-artofarc:uuid()}</result>", "result2", "data(<url>http://localhost/nix/ep</url>)");
      LinkedHashMap<String, String> ns = new LinkedHashMap<>();
      ns.put("fn-bea", "http://artofarc.com/xpath-extension");
      Action action = createAssignAction(assignments, ns);
      action.setNextAction(new DumpAction());
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action, new DumpAction());
      consumerPort.process(context, message);
      Node node = message.getVariable("result");
      if (node.getNodeType() == Node.TEXT_NODE) {
         String result = node.getTextContent();
         assertEquals("Hello", result);
      } else {
         context.getIdenticalTransformer().transform(new DOMSource(node), new StreamResult(System.out));
      }
   }
   
   @Test
   public void testCurrentUTC() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.STRING, "<test>Hello</test>");
      Map<Entry<String, Boolean>, String> assignments = createAssignments("result", "format-dateTime(adjust-dateTime-to-timezone(current-dateTime(),xs:dayTimeDuration('PT0H')),'[Y,4]-[M,2]-[D,2]T[H01]:[m01]:[s01].[f,3]Z')");
      Action action = createAssignAction(assignments, null);
      action.setNextAction(new DumpAction());
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      for (int i = 0; i < 100; ++i) {
         consumerPort.process(context, message);
         String date = message.getVariable("result").toString();
         assertEquals("Wrong length: " + date, 24, date.length());
      }
   }
   
   @Test
   public void testJavaExtensionEvaluate() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.STRING, "<test>Hello World!</test>");
      Map<Entry<String, Boolean>, String> assignments = createAssignments("result", "fn-artofarc:evaluate('test/text()')");
      assignments.put(com.artofarc.util.Collections.createEntry("result2", true), "*[2]/text()");
      Action action = createAssignAction(assignments, null);
      action.setNextAction(new DumpAction());
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      consumerPort.process(context, message);
      assertEquals("Hello World!", message.getVariable("result"));
      assertNull(message.getVariable("result2"));
   }
   
   @Test
   public void testLegacySupport() throws Exception {
      GlobalContext globalContext = context.getPoolContext().getGlobalContext();
      ESBMessage message = new ESBMessage(BodyType.INVALID, null);
      Directory modules = new Directory(globalContext.getFileSystem(), globalContext.getFileSystem().getRoot(), "modules");
      Directory queries = new Directory(globalContext.getFileSystem(), globalContext.getFileSystem().getRoot(), "queries");
		XQueryArtifact module = new XQueryArtifact(globalContext.getFileSystem(), modules, "osb-legacy-support.xqy");
		module.setContent(readFile("src/test/resources/osb-legacy-support.xqy"));
//		module = new XQueryArtifact(modules, "functx-1.0-doc-2007-01.xq");
//		module.setContent(readFile("src/test/resources/functx-1.0-doc-2007-01.xq"));
      XQueryArtifact xqueryArtifact = new XQueryArtifact(globalContext.getFileSystem(), queries, "test.xqy");
      String xqueryStr = "import module namespace fn-bea='http://osb-legacy-support' at '/modules/osb-legacy-support.xqy';\n" +
      		"(:: pragma bea:global-element-parameter parameter=\"$messageHeader1\" ::)" +
      		"(<doc>" +
      		"<messageId>M-{ fn-bea:uuid() }</messageId>" +
      		"<schadendatum>{ fn-bea:date-to-string-with-format(\"yyyy-MM-dd\", current-date()) }</schadendatum>" +
      		"<schadenuhrzeit>{ fn-bea:time-from-string-with-format(\"HHmmss\", current-time()) }</schadenuhrzeit>" +
      		"<kontonummer>{ fn-bea:format-number(4711, \"000000000000\") }</kontonummer>" +
      		"<anfragedatum>{ fn-bea:date-to-string-with-format(\"yyyy-MM-dd\", fn-bea:date-from-string-with-format(\"dd.MM.yyy\", '11.01.1970'))}</anfragedatum>" +
      		"</doc>)";
		xqueryArtifact.setContent(xqueryStr.getBytes());
      xqueryArtifact.validateInternal(globalContext);
      assertTrue(module.isValidated());
      assertTrue(xqueryArtifact.getReferenced().size() > 0);
      //assertTrue(xqueryArtifact.getReferenced().contains("/modules/osb-legacy-support-v1.xqm"));
      Action action = createTransformAction(xqueryArtifact);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
   }
   
}
