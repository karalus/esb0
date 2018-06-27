package com.artofarc.esb.action;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

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
      message.getVariables().put(ESBVariableConstants.operation, "op1");
      assertEquals("void", Action.bindVariable("void", message));
      assertEquals("{call op1}", Action.bindVariable("{call ${operation}}", message));
      assertEquals("Myop1", Action.bindVariable("My${operation}", message));
   }

}
