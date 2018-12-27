package com.artofarc.esb.action;

import static org.junit.Assert.*;

import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;


public class EncodingTest {
   
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
   public void testEncodeResponse() throws Exception {
      String test = "<test>ä</test>\n";
		ESBMessage message = new ESBMessage(BodyType.STRING, test);
      message.setSinkEncoding("utf-16");
      Action action = new AssignAction("request", ".");
      ConsumerPort consumerPort = new ConsumerPort(null);
      consumerPort.setStartAction(action);
      action = action.setNextAction(new TerminalAction(){});
      consumerPort.process(context, message);
      assertTrue(Arrays.equals(test.getBytes("utf-16"), message.getBodyAsByteArray(context)));
   }

}
