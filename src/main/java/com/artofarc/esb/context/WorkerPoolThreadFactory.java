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
package com.artofarc.esb.context;

import java.util.ArrayList;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

final class WorkerPoolThreadFactory implements ThreadFactory {

	private final ThreadGroup _threadGroup = Thread.currentThread().getThreadGroup();
	private final AtomicInteger threadNumber = new AtomicInteger();
	private final String _name, namePrefix;
	private final int _priority;

	WorkerPoolThreadFactory(String name, int priority) {
		_name = name;
		namePrefix = "WorkerPool-" + name + "-thread-";
		_priority = priority;
	}

	public String getName() {
		return _name;
	}

	@Override
	public Thread newThread(final Runnable r) {
		final Thread thread = new Thread(_threadGroup, r, namePrefix + threadNumber.incrementAndGet());
		thread.setPriority(_priority);
		return thread;
	}

	ArrayList<String> getActiveThreads() {
		ArrayList<String> result = new ArrayList<>();
		Thread[] list = new Thread[_threadGroup.activeCount()];
		int c = _threadGroup.enumerate(list);
		for (int i = 0; i < c; ++i) {
			String name = list[i].getName();
			if (name.startsWith(namePrefix)) {
				result.add(name);
			}
		}
		return result;
	}

}
