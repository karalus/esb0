package com.artofarc.esb.action;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.PoolContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.ESBVariableConstants;


public class ActionTest {
   
   private Context context;
   
   @Before
   public void createContext() throws Exception {
      context = new Context(new PoolContext(new GlobalContext()));
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
      message.getVariables().put(ESBVariableConstants.SOAP_OPERATION, "op1");
      assertEquals("void", Action.bindVariable("void", message));
      assertEquals("{call op1}", Action.bindVariable("{call ${operation}}", message));
      assertEquals("Myop1", Action.bindVariable("My${operation}", message));
   }

   @Test
   public void testResolveTemplate() throws Exception {
      ESBMessage message = new ESBMessage(BodyType.INVALID, null);
      message.getVariables().put(ESBVariableConstants.appendHttpUrlPath, "partner/4711/order/0815");
      Action action = new RESTAction("partner/{partnerId}/order/{orderId}");
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      consumerPort.process(context, message);
      assertEquals("4711", message.getVariable("partnerId"));
      assertEquals("0815", message.getVariable("orderId"));
   }

}
