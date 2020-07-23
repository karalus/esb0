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
package com.artofarc.util;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Closer implements AutoCloseable {

	protected final static Logger logger = LoggerFactory.getLogger(Closer.class);

	private final ArrayList<AutoCloseable> _closeables = new ArrayList<>();
	private final ArrayList<Future<?>> _futures = new ArrayList<>();
	private final ExecutorService _executorService;

	public Closer(ExecutorService executorService) {
		_executorService = executorService;
	}

	public static void closeQuietly(AutoCloseable closeable) {
		try {
			closeable.close();
			logger.debug("Closed " + closeable);
		} catch (Exception e) {
			logger.warn("Possible resource leak: Exception while closing " + closeable, e);
		}
	}

	public static <E extends Exception> boolean closeWithTimeout(final Object obj, ExecutorService executorService, long timeout, String context, Class<E> cls) throws E {
		final Method method;
		try {
			method = obj.getClass().getMethod("close");
		} catch (NoSuchMethodException e) {
			throw new IllegalArgumentException(obj.getClass() + " has no method close");
		}
		Future<Boolean> future = executorService.submit(new Callable<Boolean>() {

			@Override
			public Boolean call() throws Exception {
				try {
					method.invoke(obj);
				} catch (InvocationTargetException e) {
					throw ReflectionUtils.convert(e.getCause(), cls);
				}
				return true;
			}
		});
		try {
			return future.get(timeout, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			logger.warn("Possible resource leak: Could not close " + obj.getClass().getSimpleName() + " for " + context + " regularly within timeout of " + timeout + "ms");
			future.cancel(true);
			return false;
		} catch (InterruptedException e) {
			logger.warn("Possible resource leak: Closing " + obj.getClass().getSimpleName() + " for " + context + " was unexpectedly interrupted");
			return false;
		} catch (ExecutionException e) {
			throw ReflectionUtils.convert(e.getCause(), cls);
		}
	}

	public Future<?> closeAsyncUnattended(final AutoCloseable closeable) {
		return _executorService.submit(new Runnable() {

			@Override
			public void run() {
				closeQuietly(closeable);
			}
		});
	}

	public void closeAsync(AutoCloseable closeable) {
		_futures.add(closeAsyncUnattended(closeable));
	}

	public void add(AutoCloseable closeable) {
		_closeables.add(closeable);
	}

	public void submit() {
		for (AutoCloseable closeable : _closeables) {
			closeAsync(closeable);
		}
		_closeables.clear();
	}

	@Override
	public void close() throws InterruptedException, ExecutionException {
		if (_futures.size() > 0) {
			for (Iterator<Future<?>> iter = _futures.iterator(); iter.hasNext();) {
				iter.next().get();
				iter.remove();
			}
			logger.info("Closed all closables async");
		}
	}

}
