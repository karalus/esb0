package com.artofarc.esb.action;

import static org.junit.Assert.*;

import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.ESBConstants;


public class ActionTest {
   
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
   public void testBindVariable() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.STRING, "<test>Hello</test>");
      message.getVariables().put(ESBConstants.SOAP_OPERATION, "op1");
      assertEquals("void", Action.bindVariable("void", context, message));
      assertEquals("{call op1}", Action.bindVariable("{call ${operation}}", context, message));
      assertEquals("Myop1", Action.bindVariable("My${operation}", context, message));
      // new feature
      Exception exception = new Exception("my message", new IllegalArgumentException("wrong arg"));
      message.getVariables().put("exception", exception);
      assertEquals("Error: my message, wrong arg", Action.bindVariable("Error: ${exception.getMessage}, ${exception.getCause.getMessage}", context, message));
      Object object = new Object() {
      	@SuppressWarnings("unused")
			public void test() {
      		throw new IllegalStateException();
      	}
      };
      message.getVariables().put("object", object);
      try {
         Action.bindVariable("${object.test}", context, message);
      } catch (InvocationTargetException e) {
      	assertTrue(e.getCause() instanceof IllegalStateException);
      }
   }

   @Test
   public void testResolveTemplate() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.INVALID, null);
      message.getVariables().put(ESBConstants.appendHttpUrlPath, "partner/4711/order/0815");
      BranchOnPathAction action = new BranchOnPathAction("", null);
      MarkAction markAction = new MarkAction();
		action.getBranchMap().put(new BranchOnPathAction.PathTemplate("partner/{partnerId}/order/{orderId}"), markAction);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      consumerPort.process(context, message);
      assertTrue(markAction.executed);
      assertEquals("4711", message.getVariable("partnerId"));
      assertEquals("0815", message.getVariable("orderId"));
   }

   @Test
   public void testResolveQuery() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.INVALID, null);
      message.getVariables().put(ESBConstants.QueryString, "wsdl&version=1%2E0");
      BranchOnPathAction action = new BranchOnPathAction("", null);
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      consumerPort.process(context, message);
      assertTrue(message.getVariables().containsKey("wsdl"));
      assertEquals("1.0", message.getVariable("version"));
   }

   @Test
   public void testBranch() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.INVALID, null);
      MarkAction action1 = new MarkAction();
      MarkAction action2 = new MarkAction();
      MarkAction action3 = new MarkAction();
      BranchOnVariableAction branchOnVariableAction = new BranchOnVariableAction("var", action2);
      branchOnVariableAction.getBranchMap().put("ok", action1);
      branchOnVariableAction.setNextAction(action3);
      branchOnVariableAction.process(context, message);
      assertFalse(action1.executed);
      assertFalse(action2.executed);
      assertTrue(action3.executed);
      action3.executed = false;
      message.putVariable("var", "nok");
      branchOnVariableAction.process(context, message);
      assertFalse(action1.executed);
      assertTrue(action2.executed);
      assertTrue(action3.executed);
      action3.executed = false;
      action2.executed = false;
      message.putVariable("var", "ok");
      branchOnVariableAction.process(context, message);
      assertTrue(action1.executed);
      assertFalse(action2.executed);
      assertTrue(action3.executed);
   }
   
   @Test
   public void testSetMessage() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.STRING, "<test>Hello</test>");
		SetMessageAction action = new SetMessageAction(false, getClass().getClassLoader(), "${body}", "java.lang.String", null);
		action.addHeader("int", "42", "java.lang.Integer", null);
		action.addHeader("bool", "true", "java.lang.Boolean", "parseBoolean");
		action.addHeader("now", "", "java.lang.System", "currentTimeMillis");
		action.addHeader("id", "${_id.toString}", null, null);
		action.addVariable("_id", "", "java.util.UUID", "randomUUID");
   	action.setNextAction(new DumpAction());
   	action.process(context, message);
   	assertEquals(42, message.getHeader("int"));
   	assertEquals(true, message.getHeader("bool"));
   	assertTrue(message.getHeader("now") instanceof Long);
   	assertTrue(message.getHeader("id") instanceof String);
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
   	assertEquals(1L, message.getVariable("numresult"));
   	assertTrue(message.getVariable("decresult") instanceof BigDecimal);
   	assertEquals("baz", message.getHeader("h1"));
   	assertEquals(msgStr, message.getVariable("whole"));
   	assertFalse(message.getVariables().containsKey("noresult"));
   	assertEquals("{\"productName\":\"esb0\"}", message.getBodyAsString(context));
   }

}
