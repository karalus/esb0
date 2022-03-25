/*
 * Copyright 2022 Andre Karalus
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
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

import com.artofarc.esb.jms.JMSConnectionData;
import com.artofarc.esb.jms.JMSConnectionProvider;
import com.artofarc.util.XQuerySource;

/**
 * Shared among the threads of a group.
 */
public final class PoolContext extends AbstractContext {

	private final GlobalContext _globalContext;
	private final String _workerPool;
	private final ConcurrentHashMap<XQuerySource, Integer> _cachedXQueries = new ConcurrentHashMap<>();

	PoolContext(GlobalContext globalContext, String workerPool) {
		_globalContext = globalContext;
		_workerPool = workerPool;
	}

	public GlobalContext getGlobalContext() {
		return _globalContext;
	}

	public WorkerPool getWorkerPool() {
		return _globalContext.getWorkerPool(_workerPool);
	}

	ArrayList<String> getCachedXQueries() {
		ArrayList<String> result = new ArrayList<>(); 
		for (XQuerySource xquery : _cachedXQueries.keySet()) {
			result.add(xquery.toString());
		}
		return result;
	}

	void addCachedXQuery(XQuerySource xquery) {
		Integer count = _cachedXQueries.putIfAbsent(xquery, 1);
		if (count != null) {
			while (!_cachedXQueries.replace(xquery, count, ++count)) {
				count = _cachedXQueries.get(xquery);
			}
		}
	}

	void removeCachedXQuery(XQuerySource xquery) {
		Integer count;
		do {
			count = _cachedXQueries.get(xquery);
		} while (count != null && !(count == 1 ? _cachedXQueries.remove(xquery, count) : _cachedXQueries.replace(xquery, count, --count)));
	}

	int getCachedXQueriesTotal() {
		int sum = 0;
		for (Integer count : _cachedXQueries.values()) {
			sum += count;
		}
		return sum;
	}

	HashSet<String> getJMSSessionFactories() {
		HashSet<String> result = new HashSet<>();
		JMSConnectionProvider jmsConnectionProvider = getResourceFactory(JMSConnectionProvider.class);
		for (JMSConnectionData jmsConnectionData : jmsConnectionProvider.getResourceDescriptors()) {
			result.add(jmsConnectionData.toString());
		}
		return result;
	}

	@Override
	public String toString() {
		return "PoolContext [WorkerPool=" + _workerPool + "]";
	}

}
