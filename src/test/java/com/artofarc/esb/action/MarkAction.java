package com.artofarc.esb.action;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.ESBMessage;

public class MarkAction extends DumpAction {
   private boolean executed;
   
   @Override
   protected synchronized void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
      super.execute(context, execContext, message, nextActionIsPipelineStop);
      setExecuted(true);
      notify();
   }
   
   public synchronized boolean isExecuted(long timeout) throws InterruptedException {
		wait(timeout);
   	return executed;
   }

	public boolean isExecuted() {
		return executed;
	}

	public void setExecuted(boolean executed) {
		this.executed = executed;
	}
   
}