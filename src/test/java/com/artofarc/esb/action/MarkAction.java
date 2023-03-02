package com.artofarc.esb.action;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.message.ESBMessage;

public class MarkAction extends DumpAction {

	private volatile boolean executed;

	@Override
	protected synchronized void execute(Context context, ESBMessage message) throws Exception {
		super.execute(context, message);
		setExecuted(true);
		notify();
	}

	public synchronized boolean isExecuted(long timeout) throws InterruptedException {
		if (!executed)
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