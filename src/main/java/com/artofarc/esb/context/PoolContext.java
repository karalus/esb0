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

import com.artofarc.esb.jms.JMSConnectionProvider;

/**
 * Shared among the threads of a group.
 */
public class PoolContext extends AbstractContext {

	private final GlobalContext _globalContext;
	private final JMSConnectionProvider _jmsConnectionProvider;
	private final String _workerPool;

	PoolContext(GlobalContext globalContext, String workerPool) {
		_globalContext = globalContext;
		_workerPool = workerPool;
		_jmsConnectionProvider = new JMSConnectionProvider(this);
	}

	public GlobalContext getGlobalContext() {
		return _globalContext;
	}

	public WorkerPool getWorkerPool() {
		return _globalContext.getWorkerPool(_workerPool);
	}

	public JMSConnectionProvider getJMSConnectionProvider() {
		return _jmsConnectionProvider;
	}
	
	@Override
	public void close() {
		_jmsConnectionProvider.close();
		super.close();
	}

	@Override
	public String toString() {
		return "PoolContext [WorkerPool=" + _workerPool + "]";
	}

}
