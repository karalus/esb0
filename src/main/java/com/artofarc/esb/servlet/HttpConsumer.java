/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.artofarc.esb.servlet;

import java.util.Date;

import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.action.HttpServletResponseAction;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ContextPool;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.PoolContext;
import com.artofarc.esb.message.ESBMessage;

public final class HttpConsumer extends ConsumerPort implements AutoCloseable, com.artofarc.esb.mbean.HttpConsumerMXBean {

	private final String _bindPath;
	private final int _minPool, _maxPool;
	private final long _keepAlive;
	private final HttpServletResponseAction _terminalAction;
	private ContextPool _contextPool;

	public HttpConsumer(String uri, String bindPath, int minPool, int maxPool, long keepAlive, boolean supportCompression, String multipartResponse, Integer bufferSize) {
		super(uri);
		_bindPath = bindPath;
		_minPool = minPool;
		_maxPool = maxPool;
		_keepAlive = keepAlive;
		_terminalAction = new HttpServletResponseAction(supportCompression, multipartResponse, bufferSize);
	}

	public String getBindPath() {
		return _bindPath;
	}

	public ContextPool getContextPool() {
		return _contextPool;
	}

	@Override
	public void init(GlobalContext globalContext) {
		PoolContext poolContext = globalContext.getDefaultWorkerPool().getPoolContext();
		_contextPool = new ContextPool(poolContext, _minPool, _maxPool, _keepAlive, false);
	}

	@Override
	public void process(Context context, ESBMessage message) throws Exception {
		context.getExecutionStack().push(_terminalAction);
		processInternal(context, message);
	}

	@Override
	public void enable(boolean enable) throws Exception {
		super.enable(enable);
		if (!enable) {
			close();
		}
	}

	@Override
	public void close() throws Exception {
		_contextPool.close();
	}

	// Methods for monitoring
	@Override
	public int getPoolSize() {
		return _contextPool.getPoolSize();
	}

	@Override
	public Date getLastAccess() {
		return new Date(_contextPool.getLastAccess());
	}

	@Override
	public int getMinPoolSize() {
		return _minPool;
	}

	@Override
	public int getMaxPoolSize() {
		return _maxPool;
	}

	@Override
	public long getKeepAliveMillis() {
		return _keepAlive;
	}

}
