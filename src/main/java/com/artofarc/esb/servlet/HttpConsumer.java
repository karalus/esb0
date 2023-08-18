/*
 * Copyright 2021 Andre Karalus
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.artofarc.esb.servlet;

import java.util.Date;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ContextPool;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.PoolContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;

public final class HttpConsumer extends ConsumerPort implements Runnable, com.artofarc.esb.mbean.HttpConsumerMXBean {

	private final boolean _pathMapping;
	private final String _overwriteContentType;
	private final String _bindPath;
	private final String _requiredRole;
	private final int _minPoolSize, _maxPoolSize;
	private final long _keepAlive;
	private final int _resourceLimit;
	private final HttpResponseAction _terminalAction;
	private ContextPool _contextPool;
	private volatile ScheduledFuture<?> _scheduledFuture;
	private volatile long _lastPoolLimitExceeded;
	private final AtomicLong _poolLimitExceededCount = new AtomicLong();

	public HttpConsumer(String uri, int resourceLimit, String bindPath, String overwriteContentType, String requiredRole, int minPoolSize, int maxPoolSize, long keepAlive, boolean supportCompression, String multipartSubtype, String multipartOption, Integer bufferSize) {
		super(uri);
		_pathMapping = bindPath.charAt(bindPath.length() - 1) == '*';
		_bindPath = _pathMapping ? bindPath.substring(0, bindPath.length() - 1) : bindPath;
		_overwriteContentType = overwriteContentType;
		_requiredRole = requiredRole;
		_minPoolSize = minPoolSize;
		_maxPoolSize = maxPoolSize;
		_keepAlive = keepAlive;
		_resourceLimit = resourceLimit;
		_terminalAction = new HttpResponseAction(supportCompression, multipartSubtype, multipartOption, bufferSize);
	}

	public boolean isPathMapping() {
		return _pathMapping;
	}

	public String getBindPath() {
		return _bindPath;
	}

	public String getOverwriteContentType() {
		return _overwriteContentType;
	}

	public String getRequiredRole() {
		return _requiredRole;
	}

	public Context acquireContext() {
		Context context = _contextPool.getContext();
		if (context == null) {
			_lastPoolLimitExceeded = System.currentTimeMillis();
			_poolLimitExceededCount.incrementAndGet();
			logger.info("Max pool size exceeded for " + getUri());
		}
		return context;
	}

	public void releaseContext(Context context) {
		_contextPool.releaseContext(context);
	}

	public void shrinkPool() {
		_contextPool.shrinkPool();
	}

	public void init(GlobalContext globalContext) {
		PoolContext poolContext = globalContext.getDefaultWorkerPool().getPoolContext();
		_contextPool = new ContextPool(poolContext, _minPoolSize, _maxPoolSize, _keepAlive);
	}

	void processWithServletResponse(Context context, ESBMessage message) throws Exception {
		if (_resourceLimit > 0 && getCompletedTaskCount() >= _resourceLimit) {
			message.reset(BodyType.STRING, "Resource limit exhausted");
			message.getVariables().put(ESBConstants.HttpResponseCode, javax.servlet.http.HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			message.clearHeaders();
			message.putHeader(HttpConstants.HTTP_HEADER_CONTENT_TYPE, HttpConstants.HTTP_HEADER_CONTENT_TYPE_TEXT);
			ScheduledFuture<?> scheduledFuture = _scheduledFuture;
			if (scheduledFuture != null) {
				message.putHeader(HttpConstants.HTTP_HEADER_RETRY_AFTER, (int) scheduledFuture.getDelay(TimeUnit.SECONDS));
			}
			_terminalAction.process(context, message);
		} else {
			context.getExecutionStack().push(_terminalAction);
			long count = process(context, message);
			if (_resourceLimit > 0 && count == 1) {
				_scheduledFuture = context.getGlobalContext().getDefaultWorkerPool().getScheduledExecutorService().schedule(this, 60L, TimeUnit.SECONDS);
			}
		}
	}

	@Override
	public void run() {
		_scheduledFuture = null;
		_completedTaskCount.set(0L);
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

	public int getPoolSize() {
		return _contextPool.getPoolSize();
	}

	public Date getLastAccess() {
		return _contextPool.getLastAccess();
	}

	public int getMinPoolSize() {
		return _minPoolSize;
	}

	public int getMaxPoolSize() {
		return _maxPoolSize;
	}

	public long getKeepAliveMillis() {
		return _keepAlive;
	}

	public Date getLastPoolLimitExceeded() {
		long lastPoolLimitExceeded = _lastPoolLimitExceeded;
		return lastPoolLimitExceeded > 0 ? new Date(lastPoolLimitExceeded) : null;
	}

	public long getPoolLimitExceededCount() {
		return _poolLimitExceededCount.get();
	}

}
