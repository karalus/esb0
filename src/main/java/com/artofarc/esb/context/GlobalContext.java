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

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import javax.management.MBeanServer;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.xml.transform.ErrorListener;
import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;
import javax.xml.xquery.XQConnection;
import javax.xml.xquery.XQDataFactory;
import javax.xml.xquery.XQException;

import com.artofarc.esb.FileWatchEventConsumer;
import com.artofarc.esb.KafkaConsumerPort;
import com.artofarc.esb.Registry;
import com.artofarc.esb.TimerService;
import com.artofarc.esb.artifact.Artifact;
import com.artofarc.esb.artifact.FileSystem;
import com.artofarc.esb.artifact.XMLProcessingArtifact;
import com.artofarc.esb.jms.JMSConsumer;
import com.artofarc.esb.resource.LRUCacheWithExpirationFactory;
import com.artofarc.esb.servlet.HttpConsumer;
import com.artofarc.util.Closer;
import com.artofarc.util.ConcurrentResourcePool;
import com.artofarc.util.DataStructures;
import com.artofarc.util.XMLProcessorFactory;

public final class GlobalContext extends Registry implements Runnable, com.artofarc.esb.mbean.GlobalContextMXBean {

	public static final String VERSION = "esb0.version";
	public static final String BUILD_TIME = "esb0.build.time";

	private static final int DEPLOY_TIMEOUT = Integer.parseInt(System.getProperty("esb0.deploy.timeout", "60"));
	private static final int CONSUMER_IDLETIMEOUT = Integer.parseInt(System.getProperty("esb0.consumer.idletimeout", "300"));
	private static final String GLOBALPROPERTIES = System.getProperty("esb0.globalproperties");

	private final ClassLoader _classLoader;
	private final ConcurrentResourcePool<Object, String, Boolean, NamingException> _propertyCache;
	private final Map<Object, String> _localJndiObjects = new IdentityHashMap<>();
	private final Map<String, List<WeakReference<PropertyChangeListener>>> _propertyChangeListeners = new HashMap<>();
	private final URIResolver _uriResolver;
	private final XMLProcessorFactory _xmlProcessorFactory;
	private final XQConnection _xqConnection;
	private final ReentrantLock _fileSystemLock = new ReentrantLock(true);
	private volatile FileSystem _fileSystem;
	private volatile ScheduledFuture<?> _future;

	public GlobalContext(MBeanServer mbs) {
		this(GlobalContext.class.getClassLoader(), mbs, new Properties());
	}

	public GlobalContext(ClassLoader classLoader, MBeanServer mbs, Properties properties) {
		super(mbs);
		_classLoader = classLoader;
		_propertyCache = new ConcurrentResourcePool<Object, String, Boolean, NamingException>() {

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
			protected Object createResource(String key, Boolean jndi) throws NamingException {
				if (jndi == Boolean.TRUE || key.startsWith("java:")) {
					InitialContext initialContext = new InitialContext();
					try {
						return initialContext.lookup(key);
					} finally {
						initialContext.close();
					}
				}
				return System.getProperty(key, System.getenv(key));
			}
		};
		_uriResolver = new XMLProcessingArtifact.AbstractURIResolver() {

			@Override
			protected Artifact getBaseArtifact() {
				return getFileSystem().getRoot();
			}
		};
		_xmlProcessorFactory = XMLProcessorFactory.newInstance(_uriResolver);
		_xmlProcessorFactory.setErrorListener(new ErrorListener() {

			@Override
			public void warning(TransformerException exception) {
				logger.warn(_xmlProcessorFactory.getClass().getSimpleName(), exception);
			}

			@Override
			public void error(TransformerException exception) {
			}

			@Override
			public void fatalError(TransformerException exception) throws TransformerException {
			}
		});
		try {
			_xqConnection = _xmlProcessorFactory.getConnection();
		} catch (XQException e) {
			throw new RuntimeException(e);
		}
		if (properties.getProperty(VERSION) != null) {
			logger.info("ESB0 version " + properties.getProperty(VERSION) + " build time " + properties.getProperty(BUILD_TIME));
			logDependenciesVersion();
		}
		// default WorkerPool
		String workerThreads = System.getProperty("esb0.workerThreads");
		putWorkerPool(DEFAULT_WORKER_POOL, new WorkerPool(this, DEFAULT_WORKER_POOL, workerThreads != null ? Integer.parseInt(workerThreads) : 20));
		startShrinkTask();
	}

	private void startShrinkTask() {
		if (getMBeanServer() != null && CONSUMER_IDLETIMEOUT > 0) {
			_future = getDefaultWorkerPool().getScheduledExecutorService().scheduleAtFixedRate(this, CONSUMER_IDLETIMEOUT, CONSUMER_IDLETIMEOUT, TimeUnit.SECONDS);
		}
	}

	private void stopShrinkTask() {
		if (_future != null) {
			_future.cancel(false);
		}
	}

	private void logDependenciesVersion() {
		logVersion("SLF4J", "org.slf4j", "com.artofarc.esb.context.AbstractContext", "logger");
		logVersion("JAXB", "javax.xml.bind", "com.artofarc.esb.artifact.AbstractServiceArtifact", "jaxbContext");
		logVersion("SAX Parser", "javax.xml.parsers", "com.artofarc.util.XMLProcessorFactory", "SAX_PARSER_FACTORY");
		logVersion("SAX Transformer", "javax.xml.transform", "com.artofarc.util.XMLProcessorFactory", "SAX_TRANSFORMER_FACTORY");
		if (_xqConnection != null) {
			logVersion("XQJ", _xqConnection.getClass().getClassLoader().getDefinedPackage("javax.xml.xquery"), _xqConnection.getClass());
		} else {
			logger.warn("XQJ not available");
		}
		logVersion("WSDL4J", "javax.wsdl.xml", "com.artofarc.util.WSDL4JUtil", "wsdlFactory");
		logVersion("JSON", "javax.json", "com.artofarc.util.JsonFactoryHelper", "JSON_READER_FACTORY");
		logVersion("JavaMail", "javax.mail.internet", "javax.mail.internet.MimeMultipart", null);
		logVersion("metro-fi", "com.sun.xml.fastinfoset", "com.sun.xml.fastinfoset.Encoder", null);
		logVersion("XSOM", "com.sun.xml.xsom", "com.artofarc.util.XSOMHelper", "anySchema");
		logVersion("OJDBC", "oracle.jdbc", "com.artofarc.esb.jdbc.JDBCConnection", "ifcOracleConnection");
	}

	private void logVersion(String capability, String ifcPkg, String factoryClass, String factoryField) {
		try {
			if (factoryField != null) {
				Class<?> cls = Class.forName(factoryClass, true, _classLoader);
				java.lang.reflect.Field field = cls.getDeclaredField(factoryField);
				field.setAccessible(true);
				Object factory = field.get(null);
				if (factory instanceof Class) {
					Class<?> factoryCls = (Class<?>) factory;
					logVersion(capability, factoryCls.getClassLoader().getDefinedPackage(ifcPkg), factoryCls);
				} else if (factory != null) {
					logVersion(capability, field.getType().getPackage(), factory.getClass());
				}
			} else {
				Class<?> cls = Class.forName(factoryClass, false, _classLoader);
				logVersion(capability, cls.getClassLoader().getDefinedPackage(ifcPkg), cls);
			}
		} catch (ReflectiveOperationException e) {
			logger.error("Could not get factory for " + capability, e);
		}
	}

	public static void logVersion(String capability, Package ifcPackage, Class<?> factoryClass) {
		logger.info(capability + ", interface: " + ifcPackage);
		Package factoryPackage = factoryClass.getPackage();
		String impl = "package " + factoryPackage.getName();
		if (factoryPackage.getImplementationTitle() != null) {
			impl += ", " + factoryPackage.getImplementationTitle();
		}
		if (factoryPackage.getImplementationVersion() != null) {
			impl += ", version " + factoryPackage.getImplementationVersion();
		}
		logger.info(capability + ", implementation: " + impl);
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
	public <O> O lookup(String name) throws NamingException {
		return (O) _propertyCache.getResource(name, Boolean.TRUE);
	}

	public URIResolver getURIResolver() {
		return _uriResolver;
	}

	public XMLProcessorFactory getXMLProcessorFactory() {
		return _xmlProcessorFactory;
	}

	public XQDataFactory getXQDataFactory() {
		return _xqConnection;
	}

	public boolean lockFileSystem() {
		try {
			if (_fileSystemLock.tryLock(DEPLOY_TIMEOUT, TimeUnit.SECONDS)) {
				stopShrinkTask();
				return true;
			}
		} catch (InterruptedException e) {
		}
		return false;
	}

	public void unlockFileSystem() {
		startShrinkTask();
		_fileSystemLock.unlock();
	}

	public FileSystem getFileSystem() {
		return _fileSystem;
	}

	public void setFileSystem(FileSystem fileSystem) {
		_fileSystem = fileSystem;
	}

	@Override
	public void run() {
		for (HttpConsumer httpConsumer : getHttpConsumers()) {
			httpConsumer.shrinkPool();
		}
		for (JMSConsumer jmsConsumer : getJMSConsumers()) {
			jmsConsumer.adjustJMSWorkerPool();
		}
	}

	@Override
	public void close() {
		// Phase 1: Stop ingress
		for (TimerService timerService : getTimerServices()) {
			timerService.close();
		}
		for (HttpConsumer httpConsumer : getHttpConsumers()) {
			try {
				httpConsumer.enable(false);
			} catch (Exception e1) {
				// ignore
			}
		}
		for (JMSConsumer jmsConsumer : getJMSConsumers()) {
			Closer.closeQuietly(jmsConsumer);
		}
		for (FileWatchEventConsumer fileWatchEventConsumer : getFileWatchEventConsumers()) {
			fileWatchEventConsumer.close();
		}
		for (KafkaConsumerPort kafkaConsumer : getKafkaConsumers()) {
			kafkaConsumer.close();
		}
		// Phase 2
		stopShrinkTask();
		getHttpEndpointRegistry().close();
		getHttpGlobalContext().close();
		for (WorkerPool workerPool : getWorkerPools()) {
			workerPool.close();
			workerPool.getPoolContext().close();
		}
		try {
			_xqConnection.close();
		} catch (XQException e) {
			// Ignore
		}
		// Close esb0 local JNDI Objects
		DataStructures.typeSelect(_localJndiObjects.keySet(), AutoCloseable.class).forEach(Closer::closeQuietly);
		super.close();
	}

	public Object getProperty(String key) throws NamingException {
		return _propertyCache.getResource(key);
	}

	public Object putProperty(String key, Object object) {
		Object old = _propertyCache.putResource(key, object);
		notifyPropertyChangeListeners(key, old, object);
		return old;
	}

	public Object removeProperty(String key) {
		Object old = _propertyCache.removeResource(key);
		notifyPropertyChangeListeners(key, old, null);
		return old;
	}

	public void invalidatePropertyCache() throws Exception {
		_propertyCache.reset(false);
	}

	public Set<String> getCachedProperties() {
		return _propertyCache.getResourceDescriptors();
	}

	public Object putJndiObject(String key, Object jndiObject, String artifactUri) {
		Object old = putProperty(key, jndiObject);
		_localJndiObjects.remove(old);
		_localJndiObjects.put(jndiObject, artifactUri);
		return old;
	}

	public Object removeJndiObject(String key) {
		Object jndiObject = removeProperty(key);
		_localJndiObjects.remove(jndiObject);
		return jndiObject;
	}

	public String getArtifactUri(Object jndiObject) {
		return _localJndiObjects.get(jndiObject);
	}

	public String getVersion() throws NamingException {
		return (String) getProperty(VERSION);
	}

	public Set<String> getCaches() {
		LRUCacheWithExpirationFactory<?, ?> factory = getResourceFactory(LRUCacheWithExpirationFactory.class);
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

	public interface PropertyChangeListener {
		void propertyChange(String key, Object oldValue, Object newValue);
	}

	public synchronized void addPropertyChangeListener(String key, PropertyChangeListener listener) {
		DataStructures.putInCollection(_propertyChangeListeners, key, new WeakReference<PropertyChangeListener>(listener), ArrayList::new, l -> l.get() == listener);
	}

	private synchronized void notifyPropertyChangeListeners(String key, Object oldValue, Object newValue) {
		List<WeakReference<PropertyChangeListener>> listeners = _propertyChangeListeners.get(key);
		if (listeners != null) {
			for (Iterator<WeakReference<PropertyChangeListener>> iter = listeners.iterator(); iter.hasNext();) {
				PropertyChangeListener listener = iter.next().get();
				if (listener != null) {
					listener.propertyChange(key, oldValue, newValue);
				} else {
					iter.remove();
				}
			}
			if (listeners.isEmpty()) {
				_propertyChangeListeners.remove(key);
			}
		}
	}

}
