package com.artofarc.esb.action;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.ESBMessage;

public class MarkAction extends DumpAction {
   volatile boolean executed;
   
   @Override
   protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
      super.execute(context, execContext, message, nextActionIsPipelineStop);
      executed = true;
   }
   
}