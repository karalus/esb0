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
package com.artofarc.esb.context;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.xquery.XQException;

public class WorkerPoolThreadFactory extends ThreadGroup implements ThreadFactory {

	private static final ConcurrentHashMap<Thread, Context> _map = new ConcurrentHashMap<>();

	private final PoolContext _poolContext;
	private final AtomicInteger threadNumber = new AtomicInteger();
	private final String namePrefix;
	private final int _priority;

	protected WorkerPoolThreadFactory(String name, PoolContext poolContext, int priority) {
		super(name);
		_poolContext = poolContext;
		namePrefix = "WorkerPool-" + name + "-thread-";
		_priority = priority;
	}

	@Override
	public Thread newThread(final Runnable r) {
		final Thread thread = new Thread(this, new Runnable() {

			@Override
			public void run() {
				try (Context context = new Context(_poolContext)) {
					_map.put(Thread.currentThread(), context);
					r.run();
				} catch (TransformerConfigurationException | ParserConfigurationException | XQException e) {
					throw new RuntimeException(e);
				} finally {
					_map.remove(Thread.currentThread());
				}
			}
		}, namePrefix + threadNumber.incrementAndGet());
		thread.setPriority(_priority);
		return thread;
	}

	public static Context getContext() {
		return _map.get(Thread.currentThread());
	}

}
