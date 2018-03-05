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

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.xquery.XQDataSource;

import com.artofarc.esb.Registry;
import com.artofarc.esb.artifact.FileSystem;
import com.artofarc.esb.resource.XQDataSourceFactory;

public final class GlobalContext extends Registry {

	private final InitialContext _initialContext;

	private final XMLInputFactory xmlInputFactory = XMLInputFactory.newInstance();

	private final XQDataSource xqds = XQDataSourceFactory.createXQDataSource();

	private final Map<String, WorkerPool> _workerPoolMap = Collections.synchronizedMap(new HashMap<String, WorkerPool>());

	private FileSystem _fileSystem;

	public GlobalContext() {
		try {
			_initialContext = new InitialContext();
		} catch (NamingException e) {
			throw new RuntimeException(e);
		}
	}

	@SuppressWarnings("unchecked")
	public <O extends Object> O lookup(String name) throws NamingException {
		// TOREVIEW: InitialContext is not thread safe, for lookups it should be
		// safe
		return (O) _initialContext.lookup(name);
	}

	public XMLInputFactory getXMLInputFactory() {
		return xmlInputFactory;
	}

	public XQDataSource getXQDataSource() {
		return xqds;
	}

	public FileSystem getFileSystem() {
		return _fileSystem;
	}

	public void setFileSystem(FileSystem fileSystem) {
		_fileSystem = fileSystem;
	}

	public Collection<WorkerPool> getWorkerPools() {
		return _workerPoolMap.values();
	}

	public WorkerPool getWorkerPool(String name) {
		return _workerPoolMap.get(name);
	}

	public void putWorkerPool(String name, WorkerPool workerPool) {
		WorkerPool old = _workerPoolMap.put(name, workerPool);
		if (old != null) {
			old.close();
		}
	}

	@Override
	public void close() {
		for (WorkerPool workerPool : _workerPoolMap.values()) {
			workerPool.close();
		}
		try {
			_initialContext.close();
		} catch (NamingException e) {
			// Ignore
			e.printStackTrace();
		}
		// Phase2: e.g. KafkaProducer are needed in running workers
		super.close();
	}

}
