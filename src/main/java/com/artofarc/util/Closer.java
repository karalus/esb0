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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.logging.Logger;

public final class Closer implements AutoCloseable {

   protected final static Logger logger = Logger.getLogger("ESB");
   
	private final ArrayList<AutoCloseable> _closeables = new ArrayList<>();
	private final ArrayList<Future<?>> _futures = new ArrayList<>();
	private final ExecutorService _executorService;
	
	public Closer(ExecutorService executorService) {
		_executorService = executorService;
	}
	
	public final static void closeQuietly(AutoCloseable closeable) {
		try {
			closeable.close();
			logger.fine("Closed " + closeable);
		} catch (Exception e) {
			logger.warning("Possible resource leak: Exception while closing " + closeable);
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
		for (Iterator<Future<?>> iter = _futures.iterator(); iter.hasNext();) {
			iter.next().get();
			iter.remove();
		}
		logger.info("Closed all closables");
	}

}
