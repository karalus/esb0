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

import java.io.FileNotFoundException;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

import javax.wsdl.Binding;
import javax.wsdl.BindingOperation;
import javax.xml.bind.JAXBElement;

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
import com.artofarc.esb.service.*;
import com.artofarc.esb.servlet.HttpConsumer;
import com.artofarc.util.ReflectionUtils;
import com.artofarc.util.StringWrapper;
import com.artofarc.util.IOUtils;
import com.artofarc.util.WSDL4JUtil;
import com.artofarc.util.XMLProcessorFactory;
import com.artofarc.util.XQuerySource;
import com.sun.xml.xsom.XSSchemaSet;

public class ServiceArtifact extends AbstractServiceArtifact {

	public final static String FILE_EXTENSION = "xservice";
	private static final boolean USE_SAX_VALIDATION = Boolean.parseBoolean(System.getProperty("esb0.useSAXValidation"));

	private Protocol _protocol;
	private ConsumerPort _consumerPort;

	// only used during validation
	private final HashMap<String, List<Action>> _actionPipelines = new HashMap<>();
	private XMLProcessorFactory _xqConnectionFactory;

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
		if (_xqConnectionFactory == null) {
			_xqConnectionFactory = XMLProcessorFactory.newInstance(new XMLProcessingArtifact.ArtifactURIResolver(this));
		}
		return _xqConnectionFactory;
	}

	private <T> T checkBindingPresent(T binding) throws ValidationException {
		if (binding == null) {
			throw new ValidationException(this, "No binding found for protocol " + _protocol.value());
		}
		return binding;
	}

	@Override
	protected void validateInternal(GlobalContext globalContext) throws Exception {
		migrate(globalContext);
		Service service = unmarshal();
		try {
			for (ActionPipeline actionPipeline : service.getActionPipeline()) {
				_actionPipelines.put(actionPipeline.getName(), transform(globalContext, actionPipeline.getAction(), actionPipeline.getErrorHandler()));
			}
			List<Action> list = transform(globalContext, service.getAction(), service.getErrorHandler());
			switch (_protocol = service.getProtocol()) {
			case HTTP:
				Service.HttpBindURI httpBinding = checkBindingPresent(service.getHttpBindURI());
				String multipartSubtype = httpBinding.getMultipartSubtype() != null ? httpBinding.getMultipartSubtype().value() : httpBinding.getMultipartResponse() != null ? "related" : null;
				_consumerPort = new HttpConsumer(getURI(), httpBinding.getResourceLimit(), globalContext.bindProperties(httpBinding.getValue()), globalContext.bindProperties(httpBinding.getRequiredRole()), httpBinding.getMinPool(),
						httpBinding.getMaxPool(), httpBinding.getKeepAlive(), httpBinding.isSupportCompression(), multipartSubtype, httpBinding.getMultipartResponse(), httpBinding.getBufferSize());
				globalContext.checkBindHttpService((HttpConsumer) _consumerPort);
				break;
			case JMS:
				Service.JmsBinding jmsBinding = checkBindingPresent(service.getJmsBinding());
				if (jmsBinding.getAt() != null && jmsBinding.getWorkerCount() > 1) {
					throw new ValidationException(this, "With at only one worker is allowed");
				}
				int minWorkerCount = jmsBinding.getMinWorkerCount() != null ? jmsBinding.getMinWorkerCount() : jmsBinding.getWorkerCount();
				JMSConnectionData jmsConnectionData = JMSConnectionData.create(globalContext, jmsBinding.getJndiConnectionFactory(), jmsBinding.getUserName(), jmsBinding.getPassword());
				_consumerPort = new JMSConsumer(globalContext, getURI(), resolveWorkerPool(jmsBinding.getWorkerPool()), jmsConnectionData, jmsBinding.getJndiDestination(), jmsBinding.getQueueName(), jmsBinding.getTopicName(),
						jmsBinding.getSubscription(), jmsBinding.isNoLocal(), jmsBinding.isShared(), jmsBinding.getMessageSelector(), jmsBinding.getWorkerCount(), minWorkerCount, jmsBinding.getPollInterval(), jmsBinding.getTimeUnit(), jmsBinding.getAt(), jmsBinding.getMaximumRetries(), jmsBinding.getRedeliveryDelay());
				globalContext.checkBindJmsConsumer((JMSConsumer) _consumerPort);
				break;
			case TIMER:
				Service.TimerBinding timerBinding = checkBindingPresent(service.getTimerBinding());
				_consumerPort = new TimerService(getURI(), resolveWorkerPool(timerBinding.getWorkerPool()), timerBinding.getAt(), timerBinding.getTimeUnit(), timerBinding.getPeriod(),
						timerBinding.getInitialDelay(), timerBinding.isFixedDelay());
				break;
			case FILE:
				Service.FileWatchBinding fileWatchBinding = checkBindingPresent(service.getFileWatchBinding());
				_consumerPort = new FileWatchEventConsumer(globalContext, getURI(), resolveWorkerPool(fileWatchBinding.getWorkerPool()), fileWatchBinding.getDir(), fileWatchBinding.getMove(), fileWatchBinding.getMoveOnError());
				globalContext.checkBindFileWatchEventService((FileWatchEventConsumer) _consumerPort);
				break;
			case KAFKA:
				Service.KafkaBinding kafkaBinding = checkBindingPresent(service.getKafkaBinding());
				_consumerPort = new KafkaConsumerPort(getURI(), resolveWorkerPool(kafkaBinding.getWorkerPool()), kafkaBinding.getPollInterval(), createProperties(kafkaBinding.getProperty(), globalContext), kafkaBinding.getTopic(), kafkaBinding.getTimeout());
				globalContext.checkBindKafkaConsumer((KafkaConsumerPort) _consumerPort);
				break;
			default:
				_consumerPort = new ConsumerPort(getURI());
				break;
			}
			_consumerPort.setServiceFlow(list);
			_consumerPort.setEnabled(service.isEnabled());
		} finally {
			_actionPipelines.clear();
			_xqConnectionFactory = null;
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
		for (JAXBElement<? extends ActionBase> jaxbElement : actions) {
			try {
				transform(globalContext, list, jaxbElement);
			} catch (Exception e) {
				Locator sourceLocation = jaxbElement.getValue().sourceLocation();
				throw ReflectionUtils.convert(e, ValidationException.class, this, sourceLocation != null ? sourceLocation.getLineNumber() : 0);
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

	private void transform(GlobalContext globalContext, List<Action> list, JAXBElement<? extends ActionBase> actionElement) throws Exception {
		Location location = new Location(getURI(), actionElement.getValue().sourceLocation());
		switch (actionElement.getName().getLocalPart()) {
		case "http": {
			Http http = (Http) actionElement.getValue();
			List<HttpUrl> endpoints = new ArrayList<>();
			for (Http.Url url : http.getUrl()) {
				endpoints.add(new HttpUrl(globalContext.bindProperties(url.getValue()), url.getWeight(), url.isActive()));
			}
			Proxy proxy = http.getProxyUrl() != null ? globalContext.getHttpEndpointRegistry().getProxyAuthenticator().registerProxy(globalContext.bindProperties(http.getProxyUrl())) : Proxy.NO_PROXY;
			HttpCheckAlive httpCheckAlive = null;
			if (http.getCheckAliveClass() != null) {
				java.lang.ClassLoader classLoader = resolveClassLoader(globalContext, http.getClassLoader());
				@SuppressWarnings("unchecked")
				Class<? extends HttpCheckAlive> cls = (Class<? extends HttpCheckAlive>) Class.forName(http.getCheckAliveClass(), true, classLoader);
				httpCheckAlive = cls.newInstance();
			} else if (http.getCheckAliveInterval() != null) {
				httpCheckAlive = new HttpCheckAlive();
			}
			HttpEndpoint httpEndpoint = new HttpEndpoint(http.getName(), endpoints, http.getUsername(), http.getPassword(), http.getConnectionTimeout(),
					http.getRetries(), http.getCheckAliveInterval(), httpCheckAlive, getModificationTime(), proxy);
			httpEndpoint = globalContext.getHttpEndpointRegistry().validate(httpEndpoint);
			String multipartSubtype = http.getMultipartSubtype() != null ? http.getMultipartSubtype().value() : http.getMultipartRequest() != null ? "related" : null;
			addAction(list, new HttpOutboundAction(httpEndpoint, http.getReadTimeout(), http.getChunkLength(), multipartSubtype, http.getMultipartRequest()), location);
			if (http.getWorkerPool() != null) {
				addAction(list, new SpawnAction(resolveWorkerPool(http.getWorkerPool()), false, http.isJoin()), location);
			}
			addAction(list, new HttpInboundAction(), location);
			break;
		}
		case "jms": {
			Jms jms = (Jms) actionElement.getValue();
			JMSConnectionData jmsConnectionData = JMSConnectionData.create(globalContext, jms.getJndiConnectionFactory(), jms.getUserName(), jms.getPassword());
			addAction(list, new JMSAction(globalContext, jmsConnectionData, jms.getJndiDestination(), jms.getQueueName(), jms.getTopicName(), jms.isBytesMessage(),
					jms.getDeliveryMode(), jms.getPriority(), jms.getTimeToLive(), jms.getDeliveryDelay(), jms.getExpiryQueue(), jms.isReceiveFromTempQueue(), jms.getMultipart()), location);
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
		case "executeJava": {
			ExecuteJava executeJava = (ExecuteJava) actionElement.getValue();
			java.lang.ClassLoader classLoader = resolveClassLoader(globalContext, executeJava.getClassLoader());
			@SuppressWarnings("unchecked")
			Class<? extends Action> cls = (Class<? extends Action>) Class.forName(executeJava.getJavaType(), true, classLoader);
			Action action; 
			try {
				action = cls.getConstructor(java.lang.ClassLoader.class, Properties.class).newInstance(classLoader, createProperties(executeJava.getProperty(), globalContext));
			} catch (NoSuchMethodException e) {
				action = cls.newInstance();
			}
			addAction(list, action, location);
			break;
		}
		case "file": {
			File file = (File) actionElement.getValue();
			addAction(list, new FileAction(globalContext.bindProperties(file.getDir()), file.getVerb(), file.getFilename(), file.isMkdirs(), file.getAppend(), file.getZip(), file.isReadable(), file.isWritable()), location);
			break;
		}
		case "jdbcProcedure": {
			JdbcProcedure jdbcProcedure = (JdbcProcedure) actionElement.getValue();
			XSSchemaSet schemaSet = null;
			if (jdbcProcedure.getSchemaURI() != null) {
				SchemaArtifact schemaArtifact = loadArtifact(jdbcProcedure.getSchemaURI());
				addReference(schemaArtifact);
				schemaArtifact.validate(globalContext);
				schemaSet = schemaArtifact.getXSSchemaSet();
			}
			boolean[] posUsed = new boolean[jdbcProcedure.getIn().getJdbcParameter().size() + jdbcProcedure.getOut().getJdbcParameter().size()];
			addAction(list, new JDBCProcedureAction(globalContext, jdbcProcedure.getDataSource(), jdbcProcedure.getSql(), createJDBCParameters(jdbcProcedure.getIn().getJdbcParameter(), posUsed),
					createJDBCParameters(jdbcProcedure.getOut().getJdbcParameter(), posUsed), jdbcProcedure.getMaxRows(), jdbcProcedure.getTimeout(), jdbcProcedure.getKeepConnection(), schemaSet), location);
			break;
		}
		case "jdbc": {
			Jdbc jdbc = (Jdbc) actionElement.getValue();
			boolean[] posUsed = new boolean[jdbc.getJdbcParameter().size()];
			addAction(list, new JDBCSQLAction(globalContext, jdbc.getDataSource(), jdbc.getSql(), createJDBCParameters(jdbc.getJdbcParameter(), posUsed),
					jdbc.getGeneratedKeys(), jdbc.getMaxRows(), jdbc.getTimeout(), jdbc.getKeepConnection()), location);
			break;
		}
		case "setMessage": {
			SetMessage setMessage = (SetMessage) actionElement.getValue();
			java.lang.ClassLoader classLoader = resolveClassLoader(globalContext, setMessage.getClassLoader());
			SetMessageAction setMessageAction;
			if (setMessage.getBody() != null) {
				StringWrapper bodyExpr;
				if (setMessage.getBody().getFileURI() != null) {
					Artifact artifact = loadArtifact(setMessage.getBody().getFileURI());
					artifact.validate(globalContext);
					addReference(artifact);
					bodyExpr = new StringWrapper(artifact.getContentAsBytes());
				} else {
					bodyExpr = new StringWrapper(setMessage.getBody().getValue());
				}
				setMessageAction = new SetMessageAction(setMessage.isClearAll(), classLoader, bodyExpr, setMessage.getBody().getJavaType(), setMessage.getBody().getMethod());
			} else {
				setMessageAction = new SetMessageAction(setMessage.isClearAll(), classLoader, null, null, null);
			}
			for (JAXBElement<HeaderOrVariable> jaxbElement : setMessage.getHeaderOrVariable()) {
				HeaderOrVariable hov = jaxbElement.getValue();
				try {
					setMessageAction.addAssignment(hov.getName(), jaxbElement.getName().getLocalPart().equals("header"), hov.getValue(), hov.getJavaType(), hov.getMethod(), hov.getField());
				} catch (LinkageError e) {
					throw new ValidationException(this, hov.sourceLocation().getLineNumber(), e.getCause() != null ? e.getCause() : e);
				}
			}
			addAction(list, setMessageAction, location);
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
			List<AssignAction.Assignment> assignments = new ArrayList<>();
			for (Assign.Assignment assignment : assign.getAssignment()) {
				if (assignment.getVariable() != null) {
					assignments.add(new AssignAction.Assignment(assignment.getVariable(), false, assignment.getValue(), assignment.isNullable(), assignment.getType()));
				} else if (assignment.getHeader() != null) {
					assignments.add(new AssignAction.Assignment(assignment.getHeader(), true, assignment.getValue(), assignment.isNullable(), assignment.getType()));
				} else {
					throw new ValidationException(this, assignment.sourceLocation().getLineNumber(), "assignment must be either variable or header");
				}
			}
			AssignAction assignAction = new AssignAction(assignments, assign.getBody(), createNsDecls(assign.getNsDecl()).entrySet(), assign.getBindName(), assign.getContextItem(), assign.isClearAll());
			XQueryArtifact.validateXQuerySource(this, getXQConnectionFactory(), assignAction.getXQuery());
			addAction(list, assignAction, location);
			break;
		}
		case "xml2json": {
			Xml2Json xml2Json = (Xml2Json) actionElement.getValue();
			SchemaArtifact schemaArtifact = loadArtifact(xml2Json.getSchemaURI());
			addReference(schemaArtifact);
			schemaArtifact.validate(globalContext);
			addAction(list, new XML2JsonAction(schemaArtifact.getXSSchemaSet(), xml2Json.getType(), xml2Json.isJsonIncludeRoot(),
				xml2Json.isWrapperAsArrayName(), xml2Json.getNsDecl().isEmpty() ? null : createNsDecls(xml2Json.getNsDecl())), location);
			break;
		}
		case "json2xml": {
			Json2Xml json2Xml = (Json2Xml) actionElement.getValue();
			SchemaArtifact schemaArtifact = loadArtifact(json2Xml.getSchemaURI());
			addReference(schemaArtifact);
			schemaArtifact.validate(globalContext);
			addAction(list, new Json2XMLAction(schemaArtifact.getXSSchemaSet(), json2Xml.getType(), json2Xml.isJsonIncludeRoot(), json2Xml.getXmlElement(),
				json2Xml.getNsDecl().isEmpty() ? null : createNsDecls(json2Xml.getNsDecl()), json2Xml.isStreaming()), location);
			break;
		}
		case "transform": {
			Transform transform = (Transform) actionElement.getValue();
			if (transform.getXqueryURI() != null) {
				XQueryArtifact xQueryArtifact = loadArtifact(transform.getXqueryURI());
				addReference(xQueryArtifact);
				xQueryArtifact.validate(globalContext);
				addAction(list, new TransformAction(XQuerySource.create(xQueryArtifact.getContentAsBytes()), xQueryArtifact.getURI(), transform.getContextItem()), location);
			} else if (transform.getXquery() != null) {
				XQuerySource xquery = XQuerySource.create(transform.getXquery().trim());
				XQueryArtifact.validateXQuerySource(this, getXQConnectionFactory(), xquery);
				addAction(list, new TransformAction(xquery, getURI(), transform.getContextItem()), location);
			} else {
				throw new ValidationException(this, "transform has no XQuery");
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
			@SuppressWarnings("unchecked")
			List<BindingOperation> bindingOperations = binding != null ? binding.getBindingOperations() : java.util.Collections.emptyList();
			UnwrapSOAPAction unwrapSOAPAction = new UnwrapSOAPAction(unwrapSOAP.isSoap12(), unwrapSOAP.isSinglePart(), wsdlArtifact.getSchema(), bindingOperations, wsdlArtifact.getURI(), unwrapSOAP.isGetWsdl());
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
			PreSOAPHttpAction preSOAPHttpAction = new PreSOAPHttpAction(preSOAPHttp.isSoap12(), preSOAPHttp.isHeader(), preSOAPHttp.isSinglePart(), wsdlArtifact.getSchema(), binding);
			addAction(list, preSOAPHttpAction, location);
			break;
		}
		case "postSOAPHttp":
			PostSOAPHttp postSOAPHttp = (PostSOAPHttp) actionElement.getValue();
			addAction(list, new PostSOAPHttpAction(postSOAPHttp.isSoap12(), postSOAPHttp.isSinglePart()), location);
			break;
		case "validate": {
			Validate validate = (Validate) actionElement.getValue();
			SchemaArtifact schemaArtifact = loadArtifact(validate.getSchemaURI());
			addReference(schemaArtifact);
			schemaArtifact.validate(globalContext);
			boolean documentElementExpression = validate.getExpression() == "." || validate.getExpression().equals("*");
			if (USE_SAX_VALIDATION && documentElementExpression && validate.getContextItem() == null) {
				SAXValidationAction action = new SAXValidationAction(schemaArtifact.getSchema());
				addAction(list, action, location);
			} else {
				ValidateAction validateAction = new ValidateAction(schemaArtifact.getSchema(), validate.getExpression(), createNsDecls(validate.getNsDecl()).entrySet(), validate.getContextItem());
				if (!documentElementExpression) {
					XQueryArtifact.validateXQuerySource(this, getXQConnectionFactory(), validateAction.getXQuery());
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
			addAllActions(list, serviceArtifact.getConsumerPort().getServiceFlow(), location);
			break;
		case "conditional":
			Conditional conditional = (Conditional) actionElement.getValue();
			ConditionalAction conditionalAction = new ConditionalAction(conditional.getExpression(), createNsDecls(conditional.getNsDecl()).entrySet(),
				conditional.getBindName(), conditional.getContextItem(), Action.linkList(transform(globalContext, conditional.getAction(), null)));
			XQueryArtifact.validateXQuerySource(this, getXQConnectionFactory(), conditionalAction.getXQuery());
			addAction(list, conditionalAction, location);
			break;
		case "cache":
			Cache cache = (Cache) actionElement.getValue();
			addAction(list, new CacheAction(globalContext, cache.getKey(), cache.getValue(),
				Action.linkList(transform(globalContext, cache.getAction(), null)), cache.isWriteOnly(), cache.getName(), cache.getMaxSize(), cache.getTtl()), location);
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
			IterateAction iterateAction = new IterateAction(iterate.getIterable(), iterName, iterate.isRemove(), iterate.getVariable(), Action.linkList(transform(globalContext, iterate.getAction(), null)));
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
		case "deserializeMtomXop":
			addAction(list, new MtomXopDeserializeAction(), location);
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
			addAction(list, new DumpAction(dump.isBinary(), globalContext.bindProperties(dump.getDir())), location);
			break;
		case "admin":
			Admin admin = (Admin) actionElement.getValue();
			addAction(list, new AdminAction(admin.getVerb(), admin.getResourceExp()), location);
			break;
		default:
			throw new IllegalArgumentException("Unknown action " + actionElement.getName().getLocalPart());
		}
	}

	private String resolveWorkerPool(String workerPool) throws FileNotFoundException {
		if (workerPool != null) {
			WorkerPoolArtifact workerPoolArtifact = loadArtifact(workerPool + '.' + WorkerPoolArtifact.FILE_EXTENSION);
			addReference(workerPoolArtifact);
			return IOUtils.stripExt(workerPoolArtifact.getURI());
		} else {
			return null;
		}
	}

	private java.lang.ClassLoader resolveClassLoader(GlobalContext globalContext, String classLoaderURI) throws FileNotFoundException, ValidationException {
		if (classLoaderURI != null) {
			ClassLoaderArtifact classLoaderArtifact = loadArtifact(classLoaderURI + '.' + ClassLoaderArtifact.FILE_EXTENSION);
			addReference(classLoaderArtifact);
			classLoaderArtifact.validate(globalContext);
			return classLoaderArtifact.getFileSystemClassLoader();
		} else {
			return globalContext.getClassLoader();
		}
	}

	private static HashMap<String, String> createNsDecls(List<NsDecl> nsDecls) {
		HashMap<String, String> result = new HashMap<>();
		for (NsDecl nsDecl : nsDecls) {
			String prefix = nsDecl.getPrefix();
			result.put(prefix != null ? prefix : "", nsDecl.getNamespace());
		}
		return result;
	}

	private static List<JDBCParameter> createJDBCParameters(List<JdbcParameter> jdbcParameters, boolean[] posUsed) {
		List<JDBCParameter> params = new ArrayList<>(jdbcParameters.size());
		for (JdbcParameter jdbcParameter : jdbcParameters) {
			if (jdbcParameter.getPos() != null) {
				params.add(new JDBCParameter(jdbcParameter.getPos(), jdbcParameter.getType(), jdbcParameter.isBody(), jdbcParameter.isAttachments(),
						jdbcParameter.getVariable(), jdbcParameter.getTruncate(), jdbcParameter.getXmlElement()));
				posUsed[jdbcParameter.getPos() - 1] = true;
			}
		}
		int pos = 0;
		for (JdbcParameter jdbcParameter : jdbcParameters) {
			if (jdbcParameter.getPos() == null) {
				while (posUsed[pos]) ++pos;
				posUsed[pos] = true;
				params.add(new JDBCParameter(++pos, jdbcParameter.getType(), jdbcParameter.isBody(), jdbcParameter.isAttachments(),
						jdbcParameter.getVariable(), jdbcParameter.getTruncate(), jdbcParameter.getXmlElement()));
			}
		}
		return params;
	}

}
