package com.artofarc.esb.action;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

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
import com.artofarc.esb.service.XQDecl;

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
   public void testXQueryAttr() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.STRING, "<test type='text'>Hello</test>");
      Action action = createAssignAction("result", "string(test/@type)");
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action.setNextAction(new DumpAction());
      consumerPort.process(context, message);
      assertEquals("text",  message.getVariable("result"));
   }

   @Test
   public void testXQueryWithExternalVariable() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.INVALID, null);
      message.putVariable("inVar", "myValue");
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(createAssignAction("result", "$inVar", "inVar"), new DumpAction());
      consumerPort.process(context, message);
      assertEquals("myValue", message.getVariable("result"));
   }

   @Test
   public void testXQueryNS() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.STRING, "<test>Hello</test>");
      LinkedHashMap<String, String> ns = new LinkedHashMap<>();
      ns.put("v1", "http://com.artofarc/v1");
      Action action = createAssignAction(createAssignments(false, "result", "<v1:result>{test/text()}</v1:result>", "request", "."), null, ns);
      action.setNextAction(new DumpAction());
      action.process(context, message);
      Node node = message.getVariable("result");
      transform(new DOMSource(node), new StreamResult(System.out));
      Node request = message.getVariable("request");
      assertTrue(request.getNodeType() == Node.DOCUMENT_NODE);
   }

   @Test
   public void testCondition() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.STRING, "<test>Hello</test>");
      MarkAction action3 = new MarkAction();
      ConditionalAction action = new ConditionalAction("test/text() = \'Hello\'", null, Collections.<XQDecl> emptyList(), null, action3);
      MarkAction action2 = new MarkAction();
      action.setNextAction(action2);
      action.process(context, message);
      assertTrue(action3.isExecuted());
      message = new ESBMessage(BodyType.STRING, "<test>Hello World!</test>");
      action.process(context, message);
      assertTrue(action2.isExecuted());
   }
   
   @Test
   public void testJavaExtension() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.STRING, "<test>Hello</test>");
      LinkedHashMap<String, String> ns = new LinkedHashMap<>();
      ns.put("fn-bea", "http://artofarc.com/xpath-extension");
      Action action = createAssignAction(createAssignments(false, "result", "<result>{fn-bea:uuid(), fn-artofarc:uuid()}</result>", "result2", "data(<url>http://localhost/nix/ep</url>)"), null, ns);
      action.setNextAction(new DumpAction());
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action, new DumpAction());
      consumerPort.process(context, message);
      Node node = message.getVariable("result");
      if (node.getNodeType() == Node.TEXT_NODE) {
         String result = node.getTextContent();
         assertEquals("Hello", result);
      } else {
         transform(new DOMSource(node), new StreamResult(System.out));
      }
   }
   
   @Test
   public void testCurrentUTC() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.STRING, "<test>Hello</test>");
      Action action = createAssignAction(createAssignments(false, "result", "format-dateTime(adjust-dateTime-to-timezone(current-dateTime(),xs:dayTimeDuration('PT0H')),'[Y,4]-[M,2]-[D,2]T[H01]:[m01]:[s01].[f001]Z')"), null, null);
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
   public void testNullableNumber() throws Exception {
//      ESBMessage message = new ESBMessage(BodyType.STRING, "<test><number>?</number></test>");
      ESBMessage message = new ESBMessage(BodyType.STRING, "<test></test>");
      List<AssignAction.Assignment> assignments = createAssignments(false);
      assignments.add(new AssignAction.Assignment("result1", false, "if (number(*/number)) then number(*/number) else ()", true, "xs:int"));
      assignments.add(new AssignAction.Assignment("result2", false, "*/number", true, "xs:int"));
	  Action action = createAssignAction(assignments, null, null);
      action.setNextAction(new DumpAction());
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      consumerPort.process(context, message);
      assertNull(message.getVariable("result1"));
      assertNull(message.getVariable("result2"));
   }
   
   @Test
   public void testIllegalNumber() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.STRING, "<test><number>?</number></test>");
      List<AssignAction.Assignment> assignments = createAssignments(false);
      assignments.add(new AssignAction.Assignment("result1", false, "if (number(*/number)) then number(*/number) else ()", true, "xs:int"));
 	  Action action = createAssignAction(assignments, null, null);
      action.setNextAction(new DumpAction());
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      consumerPort.process(context, message);
      assertNull(message.getVariable("result"));
   }

   @Test
   public void testBoolean() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.STRING, "<test><no>75</no></test>");
      List<AssignAction.Assignment> assignments = createAssignments(false);
      assignments.add(new AssignAction.Assignment("result", false, "(not(test/bool) or xs:boolean(test/bool)) and test/no=('86','75')", false, "xs:boolean"));
 	  Action action = createAssignAction(assignments, null, null);
      action.setNextAction(new DumpAction());
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      consumerPort.process(context, message);
      assertTrue(message.getVariable("result"));
   }
   
   @Test
   public void testJavaExtensionEvaluate() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.STRING, "<test>Hello World!</test>");
      List<AssignAction.Assignment> assignments = createAssignments(false);
      assignments.add(new AssignAction.Assignment("result", false, "fn-artofarc:evaluate('test')", false, "xs:string"));
      assignments.add(new AssignAction.Assignment("result2", false, "*[2]", true, "xs:string"));
      Action action = createAssignAction(assignments, null, null);
      action.setNextAction(new DumpAction());
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      consumerPort.process(context, message);
      assertEquals("Hello World!", message.getVariable("result"));
      assertNull(message.getVariable("result2"));
   }
   
   @Test
   public void testEmbed() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.INVALID, null);
      List<AssignAction.Assignment> assignments = createAssignments(false);
      assignments.add(new AssignAction.Assignment("result1", false, "<test1/>", false, null));
      assignments.add(new AssignAction.Assignment("result2", false, "<test2/>", true, null));
      Action action = createAssignAction(assignments, "<outer>{$result1}{$result2}</outer>", null);
      action.setNextAction(new DumpAction());
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      consumerPort.process(context, message);
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
   
   @Test
   public void testArray() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.INVALID, null);
      message.putVariable("RedeliveryCount", 0);
      List<AssignAction.Assignment> assignments = createAssignments(false);
      assignments.add(new AssignAction.Assignment("NewRedeliveryCount", true, "if ($RedeliveryCount) then $RedeliveryCount + 1 else 1", false, null));
      assignments.add(new AssignAction.Assignment("deliveryDelay", false, "let $array := [10,20,40] return $array(if ($NewRedeliveryCount>array:size($array)) then array:size($array) else $NewRedeliveryCount)", false, null));
      LinkedHashMap<String, String> ns = new LinkedHashMap<>();
      ns.put("array", "http://www.w3.org/2005/xpath-functions/array");
      Action action = createAssignAction(assignments, null, ns, "RedeliveryCount");
      action.setNextAction(new DumpAction());
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      consumerPort.process(context, message);
   }
   
//   @Test
//   public void testAssignBody() throws Exception {
//      ESBMessage message = new ESBMessage(BodyType.STRING, "<test>Hello World!</test>");
//      List<AssignAction.Assignment> assignments = createAssignments(false);
//      assignments.add(new AssignAction.Assignment("request", false, "*[1]", false, null));
//      Action action = createAssignAction(assignments, "<test2/>", null);
//      ConsumerPort consumerPort = new ConsumerPort(null);
//      consumerPort.setStartAction(action);
//      action = action.setNextAction(new DumpAction());
//      List<AssignAction.Assignment> assignments2 = createAssignments(false);
//      assignments2.add(new AssignAction.Assignment("ln", false, "*/local-name()", false, null));
//      AssignAction assignAction = createAssignAction(assignments2, "$request", null, "request");
//      assignAction._xquery.prepareExpression( XQConnectionFactory.newInstance(null).getConnection(), null);
//      action = action.setNextAction(assignAction);
//      action = action.setNextAction(new DumpAction());
//      consumerPort.process(context, message);
//      assertTrue(message.getVariable("request") instanceof Node);
//   }
   
}
