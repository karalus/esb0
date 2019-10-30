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
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import javax.management.MBeanServer;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.xml.transform.URIResolver;

import com.artofarc.esb.Registry;
import com.artofarc.esb.artifact.Artifact;
import com.artofarc.esb.artifact.FileSystem;
import com.artofarc.esb.artifact.XMLProcessingArtifact;
import com.artofarc.esb.http.HttpEndpointRegistry;
import com.artofarc.esb.resource.XQConnectionFactory;

public final class GlobalContext extends Registry implements com.artofarc.esb.mbean.GlobalContextMXBean {

	private final static long deployTimeout = Long.parseLong(System.getProperty("esb0.deploy.timeout", "60"));

	private final InitialContext _initialContext;
	private final URIResolver _uriResolver;
	private final XQConnectionFactory _xqConnectionFactory;
	private final HttpEndpointRegistry httpEndpointRegistry = new HttpEndpointRegistry(this);
	private final Map<String, WorkerPool> _workerPoolMap = Collections.synchronizedMap(new HashMap<String, WorkerPool>());
	private final ReentrantLock _fileSystemLock = new ReentrantLock(true);
	
	private volatile FileSystem _fileSystem;

	public GlobalContext(MBeanServer mbs) {
		super(mbs);
		try {
			_initialContext = new InitialContext();
		} catch (NamingException e) {
			throw new RuntimeException(e);
		}
		_uriResolver = new XMLProcessingArtifact.AbstractURIResolver() {
			@Override
			protected Artifact getBaseArtifact() {
				return getFileSystem().getRoot();
			}
		};
		_xqConnectionFactory = XQConnectionFactory.newInstance(_uriResolver);
		// default WorkerPool
		String workerThreads = System.getProperty("esb0.workerThreads");
		putWorkerPool(null, new WorkerPool(this, workerThreads != null ? Integer.parseInt(workerThreads) : 20));
	}

	@SuppressWarnings("unchecked")
	public synchronized <O> O lookup(String name) throws NamingException {
		// InitialContext is not thread safe
		return (O) _initialContext.lookup(name);
	}

	public URIResolver getURIResolver() {
		return _uriResolver;
	}

	public XQConnectionFactory getXQConnectionFactory() {
		return _xqConnectionFactory;
	}

	public HttpEndpointRegistry getHttpEndpointRegistry() {
		return httpEndpointRegistry;
	}

	public boolean lockFileSystem() {
		try {
			return _fileSystemLock.tryLock(deployTimeout, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			return false;
		}
	}
	
	public void unlockFileSystem() {
		_fileSystemLock.unlock();
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

	public WorkerPool getDefaultWorkerPool() {
		return _workerPoolMap.get(null);
	}

	public void putWorkerPool(String name, WorkerPool workerPool) {
		WorkerPool old = _workerPoolMap.put(name, workerPool);
		if (old != null) {
			unregisterMBean(",group=WorkerPool,name=" + name);
		}
		registerMBean(workerPool, ",group=WorkerPool,name=" + name);
	}

	@Override
	public void close() {
		// Phase 1
		stopIngress();
		// Phase 2
		httpEndpointRegistry.close();
		for (Entry<String, WorkerPool> entry : _workerPoolMap.entrySet()) {
			WorkerPool workerPool = entry.getValue();
			workerPool.close();
			workerPool.getPoolContext().close();
			unregisterMBean(",group=WorkerPool,name=" + entry.getKey());
		}
		try {
			_initialContext.close();
		} catch (NamingException e) {
			// Ignore
		}
		super.close();
	}

}
