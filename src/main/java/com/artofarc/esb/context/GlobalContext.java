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

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import com.artofarc.esb.resource.LRUCacheWithExpirationFactory;
import com.artofarc.esb.resource.XQConnectionFactory;
import com.artofarc.esb.servlet.HttpConsumer;
import com.artofarc.util.ConcurrentResourcePool;

public final class GlobalContext extends Registry implements Runnable, com.artofarc.esb.mbean.GlobalContextMXBean {

	public static final String VERSION = "esb0.version";
	public static final String BUILD_TIME = "esb0.build.time";

	private static final int DEPLOY_TIMEOUT = Integer.parseInt(System.getProperty("esb0.deploy.timeout", "60"));
	private static final int HTTPCONSUMER_IDLETIMEOUT = Integer.parseInt(System.getProperty("esb0.httpconsumer.idletimeout", "600"));
	private static final String GLOBALPROPERTIES = System.getProperty("esb0.globalproperties");
	private static final String DEFAULT_WORKER_POOL = "default";

	private final ClassLoader _classLoader;
	private final ConcurrentResourcePool<Object, String, Void, NamingException> _propertyCache;
	private final InitialContext _initialContext;
	private final URIResolver _uriResolver;
	private final XQConnectionFactory _xqConnectionFactory;
	private final HttpEndpointRegistry httpEndpointRegistry = new HttpEndpointRegistry(this);
	private final Map<String, WorkerPool> _workerPoolMap = new ConcurrentHashMap<>();
	private final ReentrantLock _fileSystemLock = new ReentrantLock(true);
	private volatile FileSystem _fileSystem;

	public GlobalContext(ClassLoader classLoader, MBeanServer mbs, final Properties properties) {
		super(mbs);
		_classLoader = classLoader;
		if (properties.getProperty(VERSION) != null) {
			logger.info("ESB0 version " + properties.getProperty(VERSION) + " build time " + properties.getProperty(BUILD_TIME));
			logVersion("SLF4J", "org.slf4j", "com.artofarc.esb.context.AbstractContext", "logger");
			logVersion("JAXB", "javax.xml.bind", "com.artofarc.esb.artifact.AbstractServiceArtifact", "jaxbContext");
			logVersion("SAX Parser", "javax.xml.parsers", "com.artofarc.util.JAXPFactoryHelper", "SAX_PARSER_FACTORY");
			logVersion("SAX Transformer", "javax.xml.transform", "com.artofarc.util.JAXPFactoryHelper", "SAX_TRANSFORMER_FACTORY");
			logVersion("XQJ", "javax.xml.xquery", "com.saxonica.xqj.SaxonXQDataSource", null);
			logVersion("WSDL4J", "javax.wsdl.xml", "com.artofarc.util.WSDL4JUtil", "wsdlFactory");
			logVersion("JSONParser", "javax.json", "com.artofarc.util.JsonFactoryHelper", "JSON_READER_FACTORY");
			logVersion("JavaMail", "javax.mail.internet", "javax.mail.internet.MimeMultipart", null);
			logVersion("metro-fi", "com.sun.xml.fastinfoset", "com.sun.xml.fastinfoset.Encoder", null);
			logVersion("XSOM", "com.sun.xml.xsom", "com.artofarc.util.XSOMHelper", "anySchema");
		}
		_propertyCache = new ConcurrentResourcePool<Object, String, Void, NamingException>() {

			@Override
			protected void init(Map<String, Object> pool) throws Exception {
				if (GLOBALPROPERTIES != null) {
					properties.load(getResourceAsStream(GLOBALPROPERTIES));
				}
				for (String key : properties.stringPropertyNames()) {
					pool.put(key, properties.getProperty(key));
				}
			}

			@Override
			protected Object createResource(String key, Void param) throws NamingException {
				return key.startsWith("java:") ? lookup(key) : System.getProperty(key, System.getenv(key));
			}

		};
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
		putWorkerPool(DEFAULT_WORKER_POOL, new WorkerPool(this, DEFAULT_WORKER_POOL, workerThreads != null ? Integer.parseInt(workerThreads) : 20));
		if (HTTPCONSUMER_IDLETIMEOUT > 0) {
			getDefaultWorkerPool().getScheduledExecutorService().scheduleAtFixedRate(this, HTTPCONSUMER_IDLETIMEOUT, HTTPCONSUMER_IDLETIMEOUT, TimeUnit.SECONDS);
		}
	}

	private void logVersion(String capability, String ifcPkg, String factoryClass, String factoryField) {
		try {
			Class<?> cls = Class.forName(factoryClass, true, _classLoader);
			Package factoryPackage;
			if (factoryField != null) {
				java.lang.reflect.Field field = cls.getDeclaredField(factoryField);
				field.setAccessible(true);
				Object factory = field.get(null);
				factoryPackage = factory.getClass().getPackage();
			} else {
				factoryPackage = cls.getPackage();
			}
			logger.info(capability + ", interface: " + Package.getPackage(ifcPkg));
			String impl = "package " + factoryPackage.getName();
			if (factoryPackage.getImplementationTitle() != null) {
				impl += ", " + factoryPackage.getImplementationTitle();
			}
			if (factoryPackage.getImplementationVersion() != null) {
				impl += ", version " + factoryPackage.getImplementationVersion();
			}
			logger.info(capability + ", implementation: " + impl);
		} catch (ReflectiveOperationException e) {
			logger.error("Could not get factory", e);
		}
	}

	public ClassLoader getClassLoader() {
		return _classLoader;
	}

	public InputStream getResourceAsStream(String name) throws FileNotFoundException {
		InputStream stream = _classLoader.getResourceAsStream(name);
		if (stream == null) {
			throw new FileNotFoundException(name + " must be in classpath");
		}
		return stream;
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
			return _fileSystemLock.tryLock(DEPLOY_TIMEOUT, TimeUnit.SECONDS);
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
		return _workerPoolMap.get(name != null ? name : DEFAULT_WORKER_POOL);
	}

	public WorkerPool getDefaultWorkerPool() {
		return _workerPoolMap.get(DEFAULT_WORKER_POOL);
	}

	public void putWorkerPool(String name, WorkerPool workerPool) {
		WorkerPool old = _workerPoolMap.put(name, workerPool);
		if (old != null) {
			unregisterMBean(",group=WorkerPool,name=" + name);
		}
		registerMBean(workerPool, ",group=WorkerPool,name=" + name);
	}

	@Override
	public void run() {
		for (HttpConsumer httpConsumer : getHttpConsumers()) {
			httpConsumer.getContextPool().shrinkPool();
		}
	}

	@Override
	public void close() {
		// Phase 1
		stopIngress();
		// Phase 2
		httpEndpointRegistry.close();
		for (Map.Entry<String, WorkerPool> entry : _workerPoolMap.entrySet()) {
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

	public Object getProperty(String key) throws NamingException {
		return _propertyCache.getResource(key);
	}

	public void invalidatePropertyCache() throws Exception {
		_propertyCache.reset();
	}

	public Set<String> getCachedProperties() {
		return _propertyCache.getResourceDescriptors();
	}

	public String getVersion() throws NamingException {
		return (String) getProperty(VERSION);
	}

	public Set<String> getCaches() {
		@SuppressWarnings("unchecked")
		LRUCacheWithExpirationFactory<Object, Object[]> factory = getResourceFactory(LRUCacheWithExpirationFactory.class);
		return factory.getResourceDescriptors();
	}

	public String bindProperties(String exp) throws NamingException {
		if (exp == null) return null;
		StringBuilder builder = new StringBuilder();
		for (int pos = 0;;) {
			int i = exp.indexOf("${", pos);
			if (i < 0) {
				if (pos == 0) return exp;
				builder.append(exp.substring(pos));
				break;
			}
			builder.append(exp.substring(pos, i));
			int j = exp.indexOf('}', i);
			if (j < 0) throw new IllegalArgumentException("Matching } is missing");
			String name = exp.substring(i + 2, j);
			Object value = getProperty(name);
			if (value == null) {
				throw new NullPointerException(name + " is not set");
			}
			builder.append(value);
			pos = j + 1;
		}
		return builder.toString();
	}

}
