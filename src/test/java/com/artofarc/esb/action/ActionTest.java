package com.artofarc.esb.action;

import static org.junit.Assert.*;

import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Properties;

import org.junit.Before;
import org.junit.Test;

import com.artofarc.esb.AbstractESBTest;
import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.StringWrapper;
import com.artofarc.esb.message.ESBConstants;


public class ActionTest extends AbstractESBTest {
   
   @Before
   public void createContext() throws Exception {
      context = new Context(new GlobalContext(getClass().getClassLoader(), null, new Properties()).getDefaultWorkerPool().getPoolContext());
   }

   @Test
   public void testBindVariable() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.STRING, "<test>Hello</test>");
      message.getVariables().put(ESBConstants.SOAP_OPERATION, "op1");
      Action action = new DumpAction(); 
      assertEquals("void", action.bindVariable("void", context, message));
      assertEquals("{call op1}", action.bindVariable("{call ${operation}}", context, message));
      assertEquals("Myop1", action.bindVariable("My${operation}", context, message));
      // new feature
      Exception exception = new Exception("my message", new IllegalArgumentException("wrong arg"));
      message.getVariables().put("exception", exception);
      assertEquals("Error: my message, wrong arg", action.bindVariable("Error: ${exception.getMessage}, ${exception.getCause.getMessage}", context, message));
      Object object = new Object() {
      	@SuppressWarnings("unused")
			public void test() {
      		throw new IllegalStateException();
      	}
      };
      message.getVariables().put("object", object);
      try {
      	action.bindVariable("${object.test}", context, message);
      } catch (InvocationTargetException e) {
      	assertTrue(e.getCause() instanceof IllegalStateException);
      }
   }

   @Test
   public void testResolveTemplate() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.INVALID, null);
      message.getVariables().put(ESBConstants.appendHttpUrlPath, "partner/4711/order/0815");
      BranchOnPathAction action = new BranchOnPathAction("", null, false);
      MarkAction markAction = new MarkAction();
		action.addBranch("partner/{partnerId}/order/{orderId}", markAction);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      consumerPort.process(context, message);
      assertTrue(markAction.isExecuted());
      assertEquals("4711", message.getVariable("partnerId"));
      assertEquals("0815", message.getVariable("orderId"));
   }

   @Test
   public void testResolveQuery() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.INVALID, null);
      message.getVariables().put(ESBConstants.QueryString, "wsdl&version=1%2E0");
      BranchOnPathAction action = new BranchOnPathAction("", null, false);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      consumerPort.process(context, message);
      assertTrue(message.getVariables().containsKey("wsdl"));
      assertEquals("1.0", message.getVariable("version"));
   }

   @Test
   public void testResolveQueryGeneric() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.INVALID, null);
      message.getVariables().put(ESBConstants.QueryString, "wsdl&version=1%2E0");
      BranchOnPathAction action = new BranchOnPathAction("", null, true);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      consumerPort.process(context, message);
      assertEquals(Integer.valueOf(4), message.getVariable(ESBConstants.QueryString + "#"));
      assertEquals("1.0", message.getVariable(ESBConstants.QueryString + "#4"));
   }

   @Test
   public void testBranch() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.INVALID, null);
      MarkAction action1 = new MarkAction();
      MarkAction action2 = new MarkAction();
      MarkAction action3 = new MarkAction();
      BranchOnVariableAction branchOnVariableAction = new BranchOnVariableAction("var", action2, null);
      branchOnVariableAction.addBranch(getGlobalContext(), Arrays.asList("ok"), action1);
      branchOnVariableAction.setNextAction(action3);
      branchOnVariableAction.process(context, message);
      assertFalse(action1.isExecuted());
      assertFalse(action2.isExecuted());
      assertTrue(action3.isExecuted());
      action3.setExecuted(false);
      message.putVariable("var", "nok");
      branchOnVariableAction.process(context, message);
      assertFalse(action1.isExecuted());
      assertTrue(action2.isExecuted());
      assertTrue(action3.isExecuted());
      action3.setExecuted(false);
      action2.setExecuted(false);
      message.putVariable("var", "ok");
      branchOnVariableAction.process(context, message);
      assertTrue(action1.isExecuted());
      assertFalse(action2.isExecuted());
      assertTrue(action3.isExecuted());
   }

	@Test
	public void testSetMessage() throws Exception {
		ESBMessage message = new ESBMessage(BodyType.STRING, "<test>Hello</test>");
		SetMessageAction action = new SetMessageAction(false, getClass().getClassLoader(), new StringWrapper("${body}"), "java.lang.String", null);
		action.addAssignment("int", true, "42", "java.lang.Integer", null, null);
		action.addAssignment("bool", true, "true", "java.lang.Boolean", "parseBoolean", null);
		action.addAssignment("now", true, "", "java.lang.System", "currentTimeMillis", null);
		action.addAssignment("_id", false, "", "java.util.UUID", "randomUUID", null);
		action.addAssignment("id", true, "${_id.toString}", null, null, null);
		action.addAssignment("id2", true, "${id.substring(2)}", null, null, null);
		action.addAssignment("_calendar", false, "2018-11-20T16:00:41", "javax.xml.bind.DatatypeConverter", "parseDateTime", null);
		action.addAssignment("timeInMillis", false, "${_calendar.getTimeInMillis}", null, null, null);
		action.addAssignment("_addr", false, "", "java.net.InetAddress", "getLocalHost", null);
		action.addAssignment("hostname", false, "${_addr.getHostName}", null, null, null);
		action.addAssignment("date", false, "", "java.util.Date", null, null);
		action.addAssignment("_dateVoid", false, "${date.setTime(timeInMillis)}", null, null, null);
		action.addAssignment("_formatter", false, "", "java.util.Formatter", null, null);
		action.addAssignment("formattedInt", false, "${_formatter.format('%04d',int).toString}", null, null, null);
		action.addAssignment("_formatter", false, "${formatter.close}", null, null, null);
		action.setNextAction(action);
		action.setNextAction(new DumpAction());
		action.process(context, message);
		assertEquals("0042", message.getVariable("formattedInt"));
		assertEquals(null, Integer.valueOf(42), message.getHeader("int"));
		assertEquals(true, message.getHeader("bool"));
		assertTrue(message.getHeader("now") instanceof Long);
		assertTrue(message.getHeader("id") instanceof String);
		Object calendar = message.getVariable("_calendar");
		assertTrue("Type is: " + calendar.getClass(), calendar instanceof Calendar);
		Object timeInMillis = message.getVariable("timeInMillis");
		assertTrue("Type is: " + timeInMillis.getClass(), timeInMillis instanceof Long);
	}

	@Test
	public void testSetMessageJodaTime() throws Exception {
		ESBMessage message = new ESBMessage(BodyType.STRING, "<test>Hello</test>");
		SetMessageAction action = new SetMessageAction(false, getClass().getClassLoader(), null, null, null);
		action.addAssignment("Content-Type", true, "", "com.artofarc.esb.http.HttpConstants", null, "HTTP_HEADER_CONTENT_TYPE_JSON");
		action.addAssignment("now", false, "", "java.time.LocalDateTime", "now", null);
		action.addAssignment("midnight", false, "${now.withHour(0).withMinute(0).withSecond(0).withNano(0).plusDays(1)}", null, null, null);
		action.addAssignment("chronoUnit", false, "", "java.time.temporal.ChronoUnit", null, "SECONDS");
		action.addAssignment("ttl", false, "${chronoUnit.between(now,midnight)}", null, null, null);
		action.setNextAction(new DumpAction());
		action.process(context, message);
	}

   @Test
   public void testJsonPointer() throws Exception {
      String msgStr = "{\"name\":\"esb0\",\"alive\":true,\"surname\":null,\"no\":1,\"amount\":5.0,\"foo\":[\"bar\",\"baz\"]}";
		ESBMessage message = new ESBMessage(BodyType.READER, new StringReader(msgStr));
//		SetMessageAction action = new SetMessageAction(getClass().getClassLoader(), null, null, null);
//		action.addVariable("_sr", "${body}", "java.io.StringReader", null);
//		action.addVariable("_jr", "${_sr}", "javax.json.Json", "createReader");
//		action.addVariable("_jp", "/name", "javax.json.Json", "createPointer");
//		action.addHeader("name", "${_jp.toString", null, null);

      ProcessJsonAction action = new ProcessJsonAction("{\"productName\":\"${result}\"}");
      action.addHeader("h1", "/foo/1");
      action.addVariable("result", "/name");
      action.addVariable("noresult", "/surname");
      action.addVariable("boolresult", "/alive");
      action.addVariable("numresult", "/no");
      action.addVariable("decresult", "/amount");
      action.addVariable("whole", "");
   	action.setNextAction(new DumpAction());
   	action.process(context, message);
   	assertEquals("esb0", message.getVariable("result"));
   	assertEquals(true, message.getVariable("boolresult"));
   	assertEquals(null, Long.valueOf(1), message.getVariable("numresult"));
   	assertTrue(message.getVariable("decresult") instanceof BigDecimal);
   	assertEquals("baz", message.getHeader("h1"));
   	assertEquals(msgStr, message.getVariable("whole").toString());
   	assertFalse(message.getVariables().containsKey("noresult"));
   	assertEquals("{\"productName\":\"esb0\"}", message.getBodyAsString(context));
   }

	@Test
	public void testJsonArray() throws Exception {
		String msgStr = "{\"name\":\"esb0\",\"alive\":true,\"surname\":null,\"no\":1,\"amount\":5.0,\"foo\":[\"bar\",\"baz\"]}";
		ESBMessage message = new ESBMessage(BodyType.READER, new StringReader(msgStr));
		ProcessJsonAction processJsonAction = new ProcessJsonAction(null);
		processJsonAction.addVariable("foes", "/foo");
		processJsonAction.addVariable("all", "");
		Action action = processJsonAction;
		action = action.setNextAction(new IterateAction("${all.entrySet}", "_iterator", false, "_entry", new DumpAction()));
		action = action.setNextAction(new IterateAction("${foes}", "_iterator", false, "foo", new DumpAction()));
		action = action.setNextAction(new DumpAction());
		processJsonAction.process(context, message);
	}
   
	@Test
	public void testNodeList() throws Exception {
		String msgStr = "<root>\r\n" + 
				"	<node1>Text1</node1>\r\n" + 
				"	<node2>Text2</node2>\r\n" + 
				"</root>";
		ESBMessage message = new ESBMessage(BodyType.READER, new StringReader(msgStr));
	      List<AssignAction.Assignment> assignments = createAssignments(false);
	      assignments.add(new AssignAction.Assignment("nodes", false, "*/*", true, "element()"));
		AssignAction assignAction = createAssignAction(assignments, ".", null);
		Action action = assignAction;
		//action = action.setNextAction(new IterateAction("${nodes}", "_iterator", false, "node", new DumpAction()));
		action = action.setNextAction(new DumpAction());
		assignAction.process(context, message);
		//assertTrue(message.getVariable("nodes") instanceof Iterable);
	}
   
}
