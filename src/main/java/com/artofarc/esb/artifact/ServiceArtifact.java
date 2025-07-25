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
package com.artofarc.esb.artifact;

import java.net.CookiePolicy;
import java.net.Proxy;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import javax.net.ssl.SSLContext;
import javax.wsdl.Binding;
import javax.wsdl.BindingOperation;
import javax.xml.bind.JAXBElement;
import javax.xml.validation.Schema;

import org.xml.sax.Locator;

import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.FileWatchEventConsumer;
import com.artofarc.esb.KafkaConsumerPort;
import com.artofarc.esb.TimerService;
import com.artofarc.esb.action.*;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.http.HttpCheckAlive;
import com.artofarc.esb.http.HttpEndpoint;
import com.artofarc.esb.http.HttpUrl;
import com.artofarc.esb.jdbc.JDBCParameter;
import com.artofarc.esb.jms.JMSConnectionData;
import com.artofarc.esb.jms.JMSConsumer;
import com.artofarc.esb.jms.JMSConsumerGroup;
import com.artofarc.esb.service.*;
import com.artofarc.esb.servlet.HttpConsumer;
import com.artofarc.esb.servlet.HttpResponseAction;
import com.artofarc.util.ReflectionUtils;
import com.artofarc.util.StringWrapper;
import com.artofarc.util.DataStructures;
import com.artofarc.util.IOUtils;
import com.artofarc.util.WSDL4JUtil;
import com.artofarc.util.XMLProcessorFactory;
import com.artofarc.util.XQuerySource;
import com.sun.xml.bind.Locatable;
import com.sun.xml.xsom.XSSchemaSet;

public final class ServiceArtifact extends AbstractServiceArtifact {

	public final static String FILE_EXTENSION = "xservice";

	public static final boolean USE_SAX_VALIDATION = Boolean.parseBoolean(System.getProperty("esb0.useSAXValidation", "true"));

	private Protocol _protocol;
	private ConsumerPort _consumerPort;
	// only used during validation
	private HashMap<String, List<Action>> _actionPipelines;
	private XMLProcessorFactory _xmlProcessorFactory;
	private HashSet<String> _usedCaches;

	public ServiceArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	public final Protocol getProtocol() {
		return _protocol;
	}

	@SuppressWarnings("unchecked")
	public final <C extends ConsumerPort> C getConsumerPort() {
		return (C) _consumerPort;
	}

	@Override
	protected ServiceArtifact clone(FileSystem fileSystem, Directory parent) {
		ServiceArtifact clone = initClone(new ServiceArtifact(fileSystem, parent, getName()));
		clone._protocol = _protocol;
		clone._consumerPort = _consumerPort;
		return clone;
	}

	private XMLProcessorFactory getXQConnectionFactory() {
		if (_xmlProcessorFactory == null) {
			_xmlProcessorFactory = XMLProcessorFactory.newInstance(new XMLProcessingArtifact.ArtifactURIResolver(this));
		}
		return _xmlProcessorFactory;
	}

	private <T> T checkBindingPresent(T binding) throws ValidationException {
		if (binding == null) {
			throw new ValidationException(this, "No binding found for protocol " + _protocol.value());
		}
		return binding;
	}

	@Override
	protected void validateInternal(GlobalContext globalContext) throws Exception {
		Service service = unmarshal(globalContext);
		_actionPipelines = new HashMap<>();
		_usedCaches = new HashSet<>();
		try {
			for (ActionPipeline actionPipeline : service.getActionPipeline()) {
				_actionPipelines.put(actionPipeline.getName(), transform(globalContext, actionPipeline.getAction(), actionPipeline.getErrorHandler()));
			}
			List<Action> list = transform(globalContext, service.getAction(), service.getErrorHandler());
			switch (_protocol = service.getProtocol()) {
			case HTTP:
				Service.HttpBindURI httpBinding = checkBindingPresent(service.getHttpBindURI());
				String multipartSubtype = httpBinding.getMultipartSubtype() != null ? httpBinding.getMultipartSubtype().value() : httpBinding.getMultipartResponse() != null ? "related" : null;
				HttpResponseAction httpResponseAction = new HttpResponseAction(httpBinding.isSupportCompression(), multipartSubtype, httpBinding.getMultipartResponse(), httpBinding.getBufferSize());
				httpResponseAction.setLocation(new Location(getURI(), getLineNumber(httpBinding)));
				_consumerPort = new HttpConsumer(getURI(), httpBinding.getResourceLimit(), globalContext.bindProperties(httpBinding.getValue()), httpBinding.getOverwriteContentType(), globalContext.bindProperties(httpBinding.getRequiredRole()),
						httpBinding.getAsyncTimeout(), httpBinding.getMinPoolSize(), httpBinding.getMaxPoolSize(), httpBinding.getKeepAlive(), httpResponseAction);
				globalContext.checkBindHttpService(getConsumerPort());
				break;
			case JMS:
				Service.JmsBinding jmsBinding = checkBindingPresent(service.getJmsBinding());
				if (jmsBinding.getAt() != null && jmsBinding.getWorkerCount() > 1) {
					throw new ValidationException(this, "With at only one worker is allowed");
				}
				int minWorkerCount = jmsBinding.getMinWorkerCount() != null ? jmsBinding.getMinWorkerCount() : jmsBinding.getWorkerCount();
				List<JMSConnectionData> jmsConnectionDataList = JMSConnectionData.create(globalContext, jmsBinding.getJndiConnectionFactory(), jmsBinding.getUserName(), jmsBinding.getPassword(), jmsBinding.getClientID());
				if (jmsBinding.isActivePassive() && jmsConnectionDataList.size() != 2) {
					throw new ValidationException(this, getLineNumber(jmsBinding), "For active/passive we need exactly two jms factories: " + jmsBinding.getJndiConnectionFactory());
				}
				String workerPool = resolveWorkerPool(jmsBinding.getWorkerPool());
				String queueName = null, topicName = null, rootElement = null;
				XSSchemaSet schemaSet = null;
				if (jmsBinding.getQueueName() != null) {
					queueName = jmsBinding.getQueueName().getValue();
					schemaSet = resolveSchemaSet(globalContext, jmsBinding.getQueueName().getSchemaURI());
					rootElement = jmsBinding.getQueueName().getXmlElement();
				}
				if (jmsBinding.getTopicName() != null) {
					topicName = jmsBinding.getTopicName().getValue();
					schemaSet = resolveSchemaSet(globalContext, jmsBinding.getTopicName().getSchemaURI());
					rootElement = jmsBinding.getTopicName().getXmlElement();
				}
				if (jmsConnectionDataList.size() == 1 || jmsBinding.isActivePassive()) {
					_consumerPort = new JMSConsumer(globalContext, getURI(), workerPool, jmsConnectionDataList.get(0), jmsBinding.getJndiDestination(), queueName, topicName, schemaSet,
							rootElement, jmsBinding.getSubscription(), jmsBinding.isNoLocal(), jmsBinding.isShared(), jmsBinding.getMessageSelector(), jmsBinding.getWorkerCount(),
							minWorkerCount, jmsBinding.getBatchSize(), jmsBinding.getBatchTime(), jmsBinding.getPollInterval(), jmsBinding.getTimeUnit(), jmsBinding.getAt());
					globalContext.checkBindJmsConsumer(getConsumerPort());
				} else {
					_consumerPort = new JMSConsumerGroup(globalContext, getURI(), workerPool, jmsConnectionDataList, jmsBinding.getJndiDestination(), queueName, topicName, schemaSet,
							rootElement, jmsBinding.getSubscription(), jmsBinding.isNoLocal(), jmsBinding.isShared(), jmsBinding.getMessageSelector(), jmsBinding.getWorkerCount(),
							minWorkerCount, jmsBinding.getBatchSize(), jmsBinding.getBatchTime(), jmsBinding.getPollInterval(), jmsBinding.getTimeUnit(), jmsBinding.getAt());
				}
				break;
			case TIMER:
				Service.TimerBinding timerBinding = checkBindingPresent(service.getTimerBinding());
				_consumerPort = new TimerService(getURI(), resolveWorkerPool(timerBinding.getWorkerPool()), timerBinding.getAt(), timerBinding.getTimeUnit(), timerBinding.getPeriod(),
						timerBinding.getInitialDelay(), timerBinding.isFixedDelay());
				break;
			case FILE:
				Service.FileWatchBinding fileWatchBinding = checkBindingPresent(service.getFileWatchBinding());
				_consumerPort = new FileWatchEventConsumer(globalContext, getURI(), fileWatchBinding.getInitialDelay(), resolveWorkerPool(fileWatchBinding.getWorkerPool()), fileWatchBinding.getDir(), fileWatchBinding.getMove(), fileWatchBinding.getMoveOnError());
				globalContext.checkBindFileWatchEventService(getConsumerPort());
				break;
			case KAFKA:
				Service.KafkaBinding kafkaBinding = checkBindingPresent(service.getKafkaBinding());
				_consumerPort = new KafkaConsumerPort(getURI(), resolveWorkerPool(kafkaBinding.getWorkerPool()), kafkaBinding.getPollInterval(), createProperties(kafkaBinding.getProperty(), globalContext), kafkaBinding.getTopic(), kafkaBinding.getTimeout());
				globalContext.checkBindKafkaConsumer(getConsumerPort());
				break;
			default:
				_consumerPort = new ConsumerPort(getURI());
				break;
			}
			_consumerPort.setStartAction(_consumerPort.setServiceFlow(list));
			_consumerPort.setUsedCaches(_usedCaches.isEmpty() ? Collections.emptySet() : _usedCaches);
			_consumerPort.setEnabled(service.isEnabled());
		} finally {
			_actionPipelines = null;
			_xmlProcessorFactory = null;
			_usedCaches = null;
		}
	}

	private void addAction(List<Action> list, Action action, Location location) {
		action.setLocation(location);
		list.add(action);
	}

	private void addAllActions(List<Action> list, List<Action> clones, Location parent) {
		for (Action clone : clones) {
			clone.setLocation(new Location(clone.getLocation(), parent));
			list.add(clone);
		}
	}

	private List<Action> transform(GlobalContext globalContext, List<JAXBElement<? extends ActionBase>> actions, String errorHandler) throws ValidationException {
		List<Action> list = new ArrayList<>();
		for (JAXBElement<? extends ActionBase> actionElement : actions) {
			try {
				transform(globalContext, list, actionElement);
			} catch (Exception e) {
				throw ReflectionUtils.convert(e, ValidationException.class, this, getLineNumber(actionElement.getValue()));
			}
		}
		if (errorHandler != null) {
			List<Action> actionPipeline = _actionPipelines.get(errorHandler);
			if (actionPipeline == null) {
				throw new ValidationException(this, "actionPipeline not found: " + errorHandler);
			}
			Action errorHandlerPipeline = Action.linkList(actionPipeline);
			list.get(0).setErrorHandler(errorHandlerPipeline);
		}
		return list;
	}

	private static int getLineNumber(Locatable locatable) {
		Locator sourceLocation = locatable.sourceLocation();
		return sourceLocation != null ? sourceLocation.getLineNumber() : -1;
	}

	private void transform(GlobalContext globalContext, List<Action> list, JAXBElement<? extends ActionBase> actionElement) throws Exception {
		Location location = new Location(getURI(), actionElement.getValue().sourceLocation());
		switch (actionElement.getName().getLocalPart()) {
		case "http": {
			Http http = (Http) actionElement.getValue();
			List<HttpUrl> endpoints = new ArrayList<>();
			for (Http.Url url : http.getUrl()) {
				HttpUrl httpUrl = new HttpUrl(globalContext.bindProperties(url.getValue()), url.getWeight(), url.isActive());
				endpoints.add(httpUrl);
				globalContext.getHttpGlobalContext().setCookiePolicy(httpUrl, getCookiePolicy(http));
			}
			Proxy proxy = http.getProxyUrl() != null ? globalContext.getHttpGlobalContext().getProxyAuthenticator().registerProxy(globalContext.bindProperties(http.getProxyUrl())) : Proxy.NO_PROXY;
			java.lang.ClassLoader classLoader = resolveClassLoader(globalContext, http.getClassLoader());
			SSLContext sslContext = null;
			if (http.getKeyStore() != null && http.getKeyStorePassword() != null) {
				sslContext = globalContext.getHttpGlobalContext().getSSLContext(classLoader, globalContext.bindProperties(http.getKeyStore()), globalContext.bindProperties(http.getKeyStorePassword()).toCharArray());
			}
			HttpCheckAlive httpCheckAlive = null;
			if (http.getCheckAliveClass() != null) {
				@SuppressWarnings("unchecked")
				Class<? extends HttpCheckAlive> cls = (Class<? extends HttpCheckAlive>) Class.forName(http.getCheckAliveClass(), true, classLoader);
				httpCheckAlive = cls.getConstructor().newInstance();
			} else if (http.getCheckAliveInterval() != null) {
				httpCheckAlive = new HttpCheckAlive();
			}
			HttpClient.Version version = http.getVersion() != null ? HttpClient.Version.valueOf(http.getVersion().replace('/', '_').replace('.', '_')) : null;
			HttpEndpoint httpEndpoint = new HttpEndpoint(http.getName(), endpoints, http.isMultiThreaded(), http.getUsername(), http.getPassword(), http.getConnectTimeout(),
				http.getRetries() != null ? http.getRetries() : endpoints.size() - 1, http.getCheckAliveInterval(), httpCheckAlive, getModificationTime(), proxy, sslContext, version);
			httpEndpoint = globalContext.getHttpEndpointRegistry().validate(httpEndpoint);
			String multipartSubtype = http.getMultipartSubtype() != null ? http.getMultipartSubtype().value() : http.getMultipartRequest() != null ? "related" : null;
			if (http.getVersion() != null) {
				addAction(list, new HttpAction(httpEndpoint, http.getReadTimeout(), resolveWorkerPool(http.getWorkerPool()), multipartSubtype, http.getMultipartRequest()), location);
			} else {
				addAction(list, new HttpOutboundAction(httpEndpoint, http.getReadTimeout(), http.getChunkLength(), multipartSubtype, http.getMultipartRequest()), location);
				if (http.getWorkerPool() != null) {
					addAction(list, new SpawnAction(resolveWorkerPool(http.getWorkerPool()), false, false), location);
				}
				addAction(list, new HttpInboundAction(), location);
			}
			break;
		}
		case "jms": {
			Jms jms = (Jms) actionElement.getValue();
			List<JMSConnectionData> jmsConnectionData = JMSConnectionData.create(globalContext, jms.getJndiConnectionFactory(), jms.getUserName(), jms.getPassword(), jms.getClientID());
			if (jms.isActivePassive() && jmsConnectionData.size() != 2) {
				throw new ValidationException(this, getLineNumber(jms), "For active/passive we need exactly two jms factories: " + jms.getJndiConnectionFactory());
			}
			String multipartSubtype = jms.getMultipartSubtype() != null ? jms.getMultipartSubtype().value() : jms.getMultipart() != null ? "related" : null;
			if (multipartSubtype != null && !jms.isBytesMessage()) {
				throw new ValidationException(this, getLineNumber(jms), "multipart message only works with JMS BytesMessage");
			}
			String queueName = null, topicName = null;
			XSSchemaSet schemaSet = null;
			if (jms.getQueueName() != null) {
				queueName = jms.getQueueName().getValue();
				schemaSet = resolveSchemaSet(globalContext, jms.getQueueName().getSchemaURI());
			}
			if (jms.getTopicName() != null) {
				topicName = jms.getTopicName().getValue();
				schemaSet = resolveSchemaSet(globalContext, jms.getTopicName().getSchemaURI());
			}
			addAction(list, new JMSAction(globalContext, jmsConnectionData, jms.isTransacted(), jms.isActivePassive(), jms.getJndiDestination(), queueName, topicName,
					resolveWorkerPool(jms.getWorkerPool()), jms.isBytesMessage(), jms.getDeliveryMode(), jms.getPriority(), jms.getTimeToLive(), jms.getDeliveryDelay(),
					jms.getExpiryQueue(), jms.isReceiveFromTempQueue(), jms.getReplyQueue(), jms.getReceiveSelector(), multipartSubtype, jms.getMultipart(), schemaSet), location);
			break;
		}
		case "produceKafka": {
			ProduceKafka produceKafka = (ProduceKafka) actionElement.getValue();
			addAction(list, new KafkaProduceAction(globalContext, createProperties(produceKafka.getProperty(), globalContext), produceKafka.getTopic(),
					produceKafka.getPartition(), produceKafka.isBinary()), location);
			break;
		}
		case "sendMail": {
			SendMail sendMail = (SendMail) actionElement.getValue();
			addAction(list, new SendMailAction(globalContext, createProperties(sendMail.getProperty(), globalContext), resolveWorkerPool(sendMail.getWorkerPool()), sendMail.getFrom(), sendMail.getTo(), sendMail.getCc(), sendMail.getBcc(),
					sendMail.getReplyTo(), sendMail.getSubject(), sendMail.getText(), sendMail.getType(), globalContext.bindProperties(sendMail.getUserName()), globalContext.bindProperties(sendMail.getPassword())), location);
			break;
		}
		case "executeAction": {
			ExecuteAction executeAction = (ExecuteAction) actionElement.getValue();
			java.lang.ClassLoader classLoader = resolveClassLoader(globalContext, executeAction.getClassLoader());
			Action action;
			try {
				@SuppressWarnings("unchecked")
				Class<? extends Action> cls = (Class<? extends Action>) Class.forName(executeAction.getJavaType(), true, classLoader);
				try {
					action = cls.getConstructor(java.lang.ClassLoader.class, Properties.class).newInstance(classLoader, createProperties(executeAction.getProperty(), globalContext));
				} catch (NoSuchMethodException e) {
					action = cls.getConstructor().newInstance();
				}
			} catch (LinkageError e) {
				throw new ValidationException(this, getLineNumber(executeAction), e.getCause() != null ? e.getCause() : e);
			}
			addAction(list, action, location);
			break;
		}
		case "file": {
			File file = (File) actionElement.getValue();
			addAction(list, new FileAction(globalContext.bindProperties(file.getDir()), file.getAction(), file.getFilename(), file.isMkdirs(), file.getAppend(), file.getZip(), file.isReadable(), file.isWritable(), file.isOwnerOnly()), location);
			break;
		}
		case "jdbcProcedure": {
			JdbcProcedure jdbcProcedure = (JdbcProcedure) actionElement.getValue();
			boolean[] posUsed = new boolean[jdbcProcedure.getIn().getParameter().size() + jdbcProcedure.getOut().getParameter().size()];
			addAction(list, new JDBCProcedureAction(globalContext, jdbcProcedure.getDataSource(), jdbcProcedure.getSql(), createJDBCParameters(jdbcProcedure.getIn().getParameter(), posUsed), createJDBCParameters(jdbcProcedure.getOut().getParameter(), posUsed),
					jdbcProcedure.isMoreThanOneResult(), jdbcProcedure.getMaxRows(), jdbcProcedure.getTimeout(), jdbcProcedure.getKeepConnection(), resolveSchemaSet(globalContext, jdbcProcedure.getSchemaURI())), location);
			break;
		}
		case "jdbc": {
			Jdbc jdbc = (Jdbc) actionElement.getValue();
			boolean[] posUsed = new boolean[jdbc.getParameter().size()];
			addAction(list, new JDBCSQLAction(globalContext, jdbc.getDataSource(), jdbc.getSql(), createJDBCParameters(jdbc.getParameter(), posUsed),
					jdbc.getGeneratedKeys(), jdbc.isMoreThanOneResult(), jdbc.getMaxRows(), jdbc.getTimeout(), jdbc.getKeepConnection()), location);
			break;
		}
		case "update": {
			Update update = (Update) actionElement.getValue();
			java.lang.ClassLoader classLoader = resolveClassLoader(globalContext, update.getClassLoader());
			SetMessageAction action;
			if (update.getBody() != null) {
				Artifact artifact = resolveURI(globalContext, update.getBody().getFileURI(), update.getBody().getValue(), update.getBody());
				if (artifact != null) {
					action = new SetMessageAction(classLoader, update.getBody().getJavaType(), update.getBody().getMethod(), StringWrapper.create(artifact.getContentAsBytes(), artifact.getEncoding()), true);
				} else {
					action = new SetMessageAction(classLoader, update.getBody().getJavaType(), update.getBody().getMethod(), StringWrapper.create(update.getBody().getValue()), false);
				}
			} else {
				action = new SetMessageAction(classLoader, null, null, null, false);
			}
			HashSet<String> retainHeaders = new HashSet<>();
			for (HeaderOrVariable hov : update.getHeaderOrVariable()) {
				if (hov instanceof Update.Header) {
					if (((Update.Header) hov).isRetain()) {
						retainHeaders.add(hov.getName().toLowerCase(Locale.ROOT));
						if (hov.getValue().isEmpty()) {
							continue;
						}
					}
					action.addAssignment(hov.getName(), true, hov.getValue(), hov.getJavaType(), hov.getMethod(), hov.getField());
				} else {
					Artifact artifact = resolveURI(globalContext, ((Update.Variable) hov).getFileURI(), hov.getValue(), hov);
					if (artifact != null) {
						action.addAssignment(hov.getName(), false, hov.getJavaType(), hov.getMethod(), hov.getField(), StringWrapper.create(artifact.getContentAsBytes(), artifact.getEncoding()), true);
					} else {
						action.addAssignment(hov.getName(), false, hov.getValue(), hov.getJavaType(), hov.getMethod(), hov.getField());
					}
				}
			}
			if (update.isClearHeaders()) {
				action.setClearHeadersExcept(retainHeaders.isEmpty() ? Collections.emptySet() : retainHeaders);
			}
			addAction(list, action, location);
			break;
		}
		case "processJson": {
			ProcessJson processJson = (ProcessJson) actionElement.getValue();
			ProcessJsonAction processJsonAction = new ProcessJsonAction(processJson.getBody());
			for (ProcessJson.Header header : processJson.getHeader()) {
				processJsonAction.addHeader(header.getName(), header.getValue());
			}
			for (ProcessJson.Variable variable : processJson.getVariable()) {
				processJsonAction.addVariable(variable.getName(), variable.getValue());
			}
			addAction(list, processJsonAction, location);
			break;
		}
		case "assign": {
			Assign assign = (Assign) actionElement.getValue();
			resolveReferencedURIs(globalContext, assign.getReferencedURIs());
			AssignAction assignAction = new AssignAction(createAssignments(assign), assign.getBody(), createNsDecls(assign.getNsDecl()).entrySet(), assign.getBindName(), getURI(), assign.getContextItem(), assign.isClearHeaders());
			XQueryArtifact.validateXQuerySource(this, getLineNumber(assign), getXQConnectionFactory(), assignAction.getXQuery());
			addAction(list, assignAction, location);
			break;
		}
		case "xml2json": {
			Xml2Json xml2Json = (Xml2Json) actionElement.getValue();
			addAction(list, new XML2JsonAction(resolveSchemaSet(globalContext, xml2Json.getSchemaURI()), xml2Json.getType(), xml2Json.isJsonIncludeRoot(),
				xml2Json.isWrapperAsArrayName(), xml2Json.getNsDecl().isEmpty() ? null : createNsDecls(xml2Json.getNsDecl())), location);
			break;
		}
		case "json2xml": {
			Json2Xml json2Xml = (Json2Xml) actionElement.getValue();
			addAction(list, new Json2XMLAction(resolveSchemaSet(globalContext, json2Xml.getSchemaURI()), json2Xml.getType(), json2Xml.isJsonIncludeRoot(), json2Xml.getXmlElement(),
				json2Xml.getNsDecl().isEmpty() ? null : createNsDecls(json2Xml.getNsDecl()), json2Xml.isStreaming()), location);
			break;
		}
		case "transform": {
			Transform transform = (Transform) actionElement.getValue();
			resolveReferencedURIs(globalContext, transform.getReferencedURIs());
			Artifact xQueryArtifact = resolveURI(globalContext, transform.getXqueryURI(), transform.getXquery(), transform);
			if (xQueryArtifact != null) {
				addAction(list, new TransformAction(XQuerySource.create(xQueryArtifact.getContentAsBytes()), xQueryArtifact.getURI(), transform.getContextItem(), transform.isClearHeaders(), transform.getMediaType()), location);
			} else {
				XQuerySource xquery = XQuerySource.create(transform.getXquery().trim());
				XQueryArtifact.validateXQuerySource(this, getLineNumber(transform), getXQConnectionFactory(), xquery);
				addAction(list, new TransformAction(xquery, getURI(), transform.getContextItem(), transform.isClearHeaders(), transform.getMediaType()), location);
			}
			break;
		}
		case "applyXSLT": {
			ApplyXSLT applyXSLT = (ApplyXSLT) actionElement.getValue();
			XSLTArtifact xsltArtifact = loadArtifact(applyXSLT.getXslURI());
			addReference(xsltArtifact);
			xsltArtifact.validate(globalContext);
			addAction(list, new XSLTAction(xsltArtifact.getURI(), xsltArtifact.getParams()), location);
			break;
		}
		case "unwrapSOAP": {
			UnwrapSOAP unwrapSOAP = (UnwrapSOAP) actionElement.getValue();
			WSDLArtifact wsdlArtifact = loadArtifact(unwrapSOAP.getWsdlURI());
			addReference(wsdlArtifact);
			wsdlArtifact.validate(globalContext);
			Binding binding = WSDL4JUtil.getBinding(wsdlArtifact.getAllBindings(), unwrapSOAP.getBinding(), unwrapSOAP.getTransport());
			if (binding == null) {
				logger.warn("No unique binding in " + unwrapSOAP.getWsdlURI());
			}
			@SuppressWarnings("unchecked")
			List<BindingOperation> bindingOperations = binding != null ? binding.getBindingOperations() : Collections.emptyList();
			UnwrapSOAPAction unwrapSOAPAction;
			if (unwrapSOAP.isGetWsdl()) {
				unwrapSOAPAction = new com.artofarc.esb.servlet.UnwrapSOAPAction(unwrapSOAP.isSoap12(), unwrapSOAP.isSinglePart(), wsdlArtifact.getSchema(), bindingOperations, wsdlArtifact.getURI());
			} else {
				unwrapSOAPAction = new UnwrapSOAPAction(unwrapSOAP.isSoap12(), unwrapSOAP.isSinglePart(), wsdlArtifact.getSchema(), bindingOperations);
			}
			addAction(list, unwrapSOAPAction, location);
			break;
		}
		case "wrapSOAP":
			WrapSOAP wrapSOAP = (WrapSOAP) actionElement.getValue();
			addAction(list, new WrapSOAPAction(wrapSOAP.isSoap12(), wrapSOAP.isHeader(), wrapSOAP.isSinglePart()), location);
			break;
		case "preSOAPHttp": {
			PreSOAPHttp preSOAPHttp = (PreSOAPHttp) actionElement.getValue();
			WSDLArtifact wsdlArtifact = loadArtifact(preSOAPHttp.getWsdlURI());
			addReference(wsdlArtifact);
			wsdlArtifact.validate(globalContext);
			Binding binding = WSDL4JUtil.getBinding(wsdlArtifact.getAllBindings(), preSOAPHttp.getBinding(), preSOAPHttp.getTransport());
			if (binding == null) {
				logger.warn("No unique binding in " + preSOAPHttp.getWsdlURI());
			}
			PreSOAPHttpAction preSOAPHttpAction = new PreSOAPHttpAction(preSOAPHttp.isSoap12(), preSOAPHttp.isHeader(), preSOAPHttp.isSinglePart(), wsdlArtifact.getSchema(), binding);
			addAction(list, preSOAPHttpAction, location);
			break;
		}
		case "postSOAPHttp":
			PostSOAPHttp postSOAPHttp = (PostSOAPHttp) actionElement.getValue();
			addAction(list, new PostSOAPHttpAction(postSOAPHttp.isSoap12(), postSOAPHttp.isSinglePart(), postSOAPHttp.getExpectedElement()), location);
			break;
		case "validate": {
			Validate validate = (Validate) actionElement.getValue();
			Schema schema = null;
			if (validate.getSchemaURI() != null) {
				SchemaArtifact schemaArtifact = loadArtifact(validate.getSchemaURI());
				addReference(schemaArtifact);
				schemaArtifact.validate(globalContext);
				schema = schemaArtifact.getSchema();
			}
			boolean documentElementExpression = validate.getExpression() == "." || validate.getExpression().equals("*");
			if (USE_SAX_VALIDATION && documentElementExpression && validate.getContextItem() == null) {
				SAXValidationAction action = new SAXValidationAction(schema);
				addAction(list, action, location);
			} else {
				ValidateAction validateAction = new ValidateAction(schema, validate.getExpression(), createNsDecls(validate.getNsDecl()).entrySet(), validate.getContextItem());
				if (!documentElementExpression) {
					XQueryArtifact.validateXQuerySource(this, getLineNumber(validate), getXQConnectionFactory(), validateAction.getXQuery());
				}
				addAction(list, validateAction, location);
			}
			break;
		}
		case "actionPipelineRef": {
			ActionPipelineRef actionPipelineRef = (ActionPipelineRef) actionElement.getValue();
			List<Action> actionPipeline = _actionPipelines.get(actionPipelineRef.getRef());
			if (actionPipeline == null) {
				throw new ValidationException(this, "actionPipeline not found: " + actionPipelineRef.getRef());
			}
			addAllActions(list, Action.cloneService(actionPipeline), location);
			break;
		}
		case "internalService":
			InternalService internalService = (InternalService) actionElement.getValue();
			ServiceArtifact serviceArtifact = loadArtifact(internalService.getServiceURI() + '.' + FILE_EXTENSION);
			addReference(serviceArtifact);
			serviceArtifact.validate(globalContext);
			addAllActions(list, serviceArtifact.getConsumerPort().cloneServiceFlow(), location);
			break;
		case "conditional":
			Conditional conditional = (Conditional) actionElement.getValue();
			resolveReferencedURIs(globalContext, conditional.getReferencedURIs());
			ConditionalAction conditionalAction = new ConditionalAction(createAssignments(conditional), conditional.getBody(), createNsDecls(conditional.getNsDecl()).entrySet(), conditional.getBindName(),
				getURI(), conditional.getContextItem(), conditional.isClearHeaders(), conditional.getExpression(), Action.linkList(transform(globalContext, conditional.getAction(), null)), conditional.isProceed());
			XQueryArtifact.validateXQuerySource(this, getLineNumber(conditional), getXQConnectionFactory(), conditionalAction.getXQuery());
			addAction(list, conditionalAction, location);
			break;
		case "cache":
			Cache cache = (Cache) actionElement.getValue();
			addAction(list, new CacheAction(globalContext, cache.getKey(), cache.isNullable(), cache.getValue(),
				Action.linkList(transform(globalContext, cache.getAction(), null)), cache.isWriteOnly(), cache.getName(), cache.getMaxSize(), cache.getTtl()), location);
			_usedCaches.add(cache.getName());
			break;
		case "uncache":
			Uncache uncache = (Uncache) actionElement.getValue();
			addAction(list, new UncacheAction(globalContext, uncache.getKey(), uncache.getName()), location);
			break;
		case "spawn":
			Spawn spawn = (Spawn) actionElement.getValue();
			addAction(list, new SpawnAction(resolveWorkerPool(spawn.getWorkerPool()), spawn.isUsePipe(), spawn.isJoin()), location);
			break;
		case "fork":
			Fork fork = (Fork) actionElement.getValue();
			ForkAction forkAction = new ForkAction(resolveWorkerPool(fork.getWorkerPool()), fork.isCopyMessage(), fork.isCopyHeaders(),
				fork.isCopyAttachments(), Action.linkList(transform(globalContext, fork.getAction(), fork.getErrorHandler())));
			addAction(list, forkAction, location);
			break;
		case "iterate":
			Iterate iterate = (Iterate) actionElement.getValue();
			String iterName = iterate.getIterName() != null ? iterate.getIterName() : "_iter" + location;
			IterateAction iterateAction = new IterateAction(iterate.getIterable(), iterName, iterate.isRemove(), iterate.getVariable(), iterate.getCollectorName(), iterate.getCollect(), Action.linkList(transform(globalContext, iterate.getAction(), null)));
			addAction(list, iterateAction, location);
			break;
		case "branchOnVariable": {
			BranchOnVariable branchOnVariable = (BranchOnVariable) actionElement.getValue();
			Action defaultAction = null;
			if (branchOnVariable.getDefault() != null) {
				defaultAction = Action.linkList(transform(globalContext, branchOnVariable.getDefault().getAction(), null));
			}
			Action nullAction = null;
			if (branchOnVariable.getNull() != null) {
				nullAction = Action.linkList(transform(globalContext, branchOnVariable.getNull().getAction(), null));
			}
			if (branchOnVariable.getDefaultOrNull() != null) {
				nullAction = defaultAction = Action.linkList(transform(globalContext, branchOnVariable.getDefaultOrNull().getAction(), null));
			}
			BranchOnVariableAction branchOnVariableAction = new BranchOnVariableAction(branchOnVariable.getVariable(), defaultAction, nullAction);
			for (BranchOnVariable.Branch branch : branchOnVariable.getBranch()) {
				if (branch.getRegEx() != null) {
					branchOnVariableAction.addBranchRegEx(branch.getRegEx(), Action.linkList(transform(globalContext, branch.getAction(), null)));
				} else {
					List<String> values = branch.getValue().isEmpty() ? Collections.singletonList("") : branch.getValue();
					branchOnVariableAction.addBranch(globalContext, values, Action.linkList(transform(globalContext, branch.getAction(), null)));
				}
			}
			addAction(list, branchOnVariableAction, location);
			break;
		}
		case "branchOnPath": {
			BranchOnPath branchOnPath = (BranchOnPath) actionElement.getValue();
			Action defaultAction = null;
			if (branchOnPath.getDefault() != null) {
				defaultAction = Action.linkList(transform(globalContext, branchOnPath.getDefault().getAction(), null));
			}
			BranchOnPathAction branchOnPathAction = new BranchOnPathAction(branchOnPath.getBasePath(), defaultAction, branchOnPath.isGenericQuery());
			for (BranchOnPath.Branch branch : branchOnPath.getBranch()) {
				branchOnPathAction.addBranch(branch.getPathTemplate(), Action.linkList(transform(globalContext, branch.getAction(), null)));
			}
			addAction(list, branchOnPathAction, location);
			break;
		}
		case "deserializeXop":
			addAction(list, new XOPDeserializeAction(), location);
			break;
		case "serializeXop":
			SerializeXop serializeXop = (SerializeXop) actionElement.getValue();
			addAction(list, new XOPSerializeAction(serializeXop.getThreshold(), serializeXop.getContentType(), serializeXop.getIfAccepts()), location);
			break;
		case "suspend":
			Suspend suspend = (Suspend) actionElement.getValue();
			addAction(list, new SuspendAction(suspend.getCorrelationID(), suspend.getTimeout()), location);
			break;
		case "resume":
			Resume resume = (Resume) actionElement.getValue();
			addAction(list, new ResumeAction(resume.getWorkerPool(), resume.getCorrelationID()), location);
			break;
		case "throwException":
			ThrowException throwException = (ThrowException) actionElement.getValue();
			addAction(list, new ThrowExceptionAction(throwException.getMessage()), location);
			break;
		case "dump":
			Dump dump = (Dump) actionElement.getValue();
			addAction(list, new DumpAction(dump.getLog(), dump.isBinary(), globalContext.bindProperties(dump.getDir())), location);
			break;
		case "admin":
			Admin admin = (Admin) actionElement.getValue();
			addAction(list, new AdminAction(admin.getVerb(), admin.getResourceExp()), location);
			break;
		default:
			throw new IllegalArgumentException("Unknown action " + actionElement.getName().getLocalPart());
		}
	}

	private String resolveWorkerPool(String workerPool) throws Exception {
		if (workerPool != null) {
			WorkerPoolArtifact workerPoolArtifact = loadArtifact(workerPool + '.' + WorkerPoolArtifact.FILE_EXTENSION);
			addReference(workerPoolArtifact);
			return IOUtils.stripExt(workerPoolArtifact.getURI());
		}
		return null;
	}

	private XSSchemaSet resolveSchemaSet(GlobalContext globalContext, String schemaURI) throws Exception {
		if (schemaURI != null) {
			SchemaArtifact schemaArtifact = loadArtifact(schemaURI);
			addReference(schemaArtifact);
			schemaArtifact.validate(globalContext);
			return schemaArtifact.getXSSchemaSet();
		}
		return null;
	}

	private void resolveReferencedURIs(GlobalContext globalContext, List<String> referencedURIs) throws Exception {
		for (String referencedURI : referencedURIs) {
			Artifact artifact = loadArtifact(referencedURI);
			artifact.validate(globalContext);
			addReference(artifact);
		}
	}

	private Artifact resolveURI(GlobalContext globalContext, String referencedURI, String value, Locatable locatable) throws Exception {
		if (referencedURI != null) {
			if (value.length() > 0) {
				throw new ValidationException(this, getLineNumber(locatable), "if uri is given value can not be used");
			}
			Artifact artifact = loadArtifact(referencedURI);
			artifact.validate(globalContext);
			addReference(artifact);
			return artifact;
		}
		return null;
	}

	private static Map<String, String> createNsDecls(List<NsDecl> nsDecls) {
		Map<String, String> result = DataStructures.createHashMap(nsDecls.size());
		for (NsDecl nsDecl : nsDecls) {
			String prefix = nsDecl.getPrefix();
			result.put(prefix != null ? prefix : "", nsDecl.getNamespace());
		}
		return result;
	}

	private List<AssignAction.Assignment> createAssignments(Assign assign) throws ValidationException {
		List<AssignAction.Assignment> assignments = new ArrayList<>();
		for (Assign.Assignment assignment : assign.getAssignment()) {
			if (assignment.getVariable() != null) {
				assignments.add(new AssignAction.Assignment(assignment.getVariable(), false, assignment.getValue(), assignment.isNullable(), assignment.getType()));
			} else if (assignment.getHeader() != null) {
				assignments.add(new AssignAction.Assignment(assignment.getHeader(), true, assignment.getValue(), assignment.isNullable(), assignment.getType()));
			} else {
				throw new ValidationException(this, getLineNumber(assignment), "assignment must be either variable or header");
			}
		}
		return assignments;
	}

	private static List<JDBCParameter> createJDBCParameters(List<Parameter> jdbcParameters, boolean[] posUsed) {
		List<JDBCParameter> params = DataStructures.createList(jdbcParameters.size());
		for (Parameter jdbcParameter : jdbcParameters) {
			if (jdbcParameter.getPos() != null) {
				params.add(new JDBCParameter(jdbcParameter.getPos(), jdbcParameter.getType(), jdbcParameter.isFree(), jdbcParameter.isBody(),
						jdbcParameter.isAttachments(), jdbcParameter.getVariable(), jdbcParameter.getTruncate(), jdbcParameter.getXmlElement()));
				posUsed[jdbcParameter.getPos() - 1] = true;
			}
		}
		int pos = 0;
		for (Parameter jdbcParameter : jdbcParameters) {
			if (jdbcParameter.getPos() == null) {
				while (posUsed[pos]) ++pos;
				posUsed[pos] = true;
				params.add(new JDBCParameter(++pos, jdbcParameter.getType(), false, jdbcParameter.isBody(),
						jdbcParameter.isAttachments(), jdbcParameter.getVariable(), jdbcParameter.getTruncate(), jdbcParameter.getXmlElement()));
			}
		}
		return params;
	}

	private static CookiePolicy getCookiePolicy(Http http) {
		if (http.getCookiePolicy() != null) {
			switch (http.getCookiePolicy()) {
			case ACCEPT_ALL:
				return CookiePolicy.ACCEPT_ALL;
			case ACCEPT_NONE:
				return CookiePolicy.ACCEPT_NONE;
			case ACCEPT_ORIGINAL_SERVER:
				return CookiePolicy.ACCEPT_ORIGINAL_SERVER;
			}
		}
		return null;
	}

}
