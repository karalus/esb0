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
package com.artofarc.esb.artifact;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;

import javax.wsdl.Binding;
import javax.wsdl.BindingOperation;
import javax.xml.bind.JAXBElement;
import javax.xml.xquery.XQConnection;
import javax.xml.xquery.XQException;

import org.xml.sax.Locator;

import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.TimerService;
import com.artofarc.esb.action.*;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.XQuerySource;
import com.artofarc.esb.http.HttpEndpoint;
import com.artofarc.esb.http.HttpUrl;
import com.artofarc.esb.jdbc.JDBCParameter;
import com.artofarc.esb.jms.JMSConnectionData;
import com.artofarc.esb.jms.JMSConsumer;
import com.artofarc.esb.resource.XQConnectionFactory;
import com.artofarc.esb.service.*;
import com.artofarc.esb.servlet.HttpConsumer;
import com.artofarc.util.Collections;
import com.artofarc.util.ReflectionUtils;
import com.artofarc.util.IOUtils;
import com.artofarc.util.WSDL4JUtil;

public class ServiceArtifact extends AbstractServiceArtifact {

	public final static String FILE_EXTENSION = "xservice";
	private static final boolean USE_SAX_VALIDATION = Boolean.parseBoolean(System.getProperty("esb0.useSAXValidation"));

	private Protocol _protocol;
	private ConsumerPort _consumerPort;

	// only used during validation
	private final HashMap<String, List<Action>> _actionPipelines = new HashMap<>();
	private XQConnection _connection;

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

	private XQConnection getConnection() throws XQException {
		if (_connection == null) {
			_connection = XQConnectionFactory.newInstance(new XMLProcessingArtifact.ArtifactURIResolver(this)).getConnection();
		}
		return _connection;
	}

	@Override
	protected void validateInternal(GlobalContext globalContext) throws Exception {
		Service service = unmarshal();
		try {
			for (ActionPipeline actionPipeline : service.getActionPipeline()) {
				_actionPipelines.put(actionPipeline.getName(), transform(globalContext, actionPipeline.getAction(), actionPipeline.getErrorHandler()));
			}
			List<Action> list = transform(globalContext, service.getAction(), service.getErrorHandler());
			switch (_protocol = service.getProtocol()) {
			case HTTP:
				final Service.HttpBindURI httpBinding = service.getHttpBindURI();
				_consumerPort = new HttpConsumer(getURI(), service.getResourceLimit(), globalContext.bindProperties(httpBinding.getValue()), httpBinding.getRequiredRole(), httpBinding.getMinPool(),
						httpBinding.getMaxPool(), httpBinding.getKeepAlive(), httpBinding.isSupportCompression(), httpBinding.getMultipartResponse(), httpBinding.getBufferSize());
				break;
			case JMS:
				final Service.JmsBinding jmsBinding = service.getJmsBinding();
				JMSConnectionData jmsConnectionData = new JMSConnectionData(globalContext, jmsBinding.getJndiConnectionFactory(), jmsBinding.getUserName(), jmsBinding.getPassword());
				_consumerPort = new JMSConsumer(globalContext, getURI(), resolveWorkerPool(jmsBinding.getWorkerPool()), jmsConnectionData, jmsBinding.getJndiDestination(), jmsBinding.getQueueName(),
						jmsBinding.getTopicName(), jmsBinding.getSubscription(), jmsBinding.getMessageSelector(), jmsBinding.getWorkerCount(), jmsBinding.getPollInterval());
				break;
			case TIMER:
				final Service.TimerBinding timerBinding = service.getTimerBinding();
				_consumerPort = new TimerService(getURI(), resolveWorkerPool(timerBinding.getWorkerPool()), timerBinding.getAt(), timerBinding.getTimeUnit(), timerBinding.getPeriod(),
						timerBinding.getInitialDelay(), timerBinding.isFixedDelay());
				break;
			default:
				_consumerPort = new ConsumerPort(getURI());
				break;
			}
			_consumerPort.setServiceFlow(list);
			_consumerPort.setEnabled(service.isEnabled());
		} finally {
			_actionPipelines.clear();
			if (_connection != null) {
				_connection.close();
				_connection = null;
			}
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
			HttpEndpoint httpEndpoint = new HttpEndpoint(http.getName(), endpoints, http.getUsername(), http.getPassword(), http.getConnectionTimeout(), http.getRetries(), http.getCheckAliveInterval(), http.getKeepAliveInterval(), getModificationTime());
			httpEndpoint = globalContext.getHttpEndpointRegistry().validate(httpEndpoint);
			addAction(list, new HttpOutboundAction(httpEndpoint, http.getReadTimeout(), http.getChunkLength(), http.getMultipartRequest()), location);
			if (http.getWorkerPool() != null) {
				addAction(list, new SpawnAction(resolveWorkerPool(http.getWorkerPool()), false, http.isJoin()), location);
			}
			addAction(list, new HttpInboundAction(), location);
			break;
		}
		case "jms": {
			Jms jms = (Jms) actionElement.getValue();
			JMSConnectionData jmsConnectionData = new JMSConnectionData(globalContext, jms.getJndiConnectionFactory(), jms.getUserName(), jms.getPassword());
			addAction(list, new JMSAction(globalContext, jmsConnectionData, jms.getJndiDestination(), jms.getQueueName(), jms.getTopicName(), jms
					.isBytesMessage(), jms.getDeliveryMode(), jms.getPriority(), jms.getTimeToLive(), jms.isReceiveFromTempQueue()), location);
			break;
		}
		case "produceKafka": {
			ProduceKafka produceKafka = (ProduceKafka) actionElement.getValue();
			Properties properties = new Properties();
			for (Property property : produceKafka.getProperty()) {
				properties.put(property.getKey(), property.getValue());
			}
			addAction(list, new KafkaProduceAction(globalContext, properties, produceKafka.getTopic(), produceKafka.getPartition(), produceKafka.isBinary()), location);
			break;
		}
		case "consumeKafka": {
			ConsumeKafka consumeKafka = (ConsumeKafka) actionElement.getValue();
			Properties properties = new Properties();
			for (Property property : consumeKafka.getProperty()) {
				properties.put(property.getKey(), property.getValue());
			}
			KafkaConsumeAction kafkaConsumeAction = new KafkaConsumeAction(properties, consumeKafka.getTopic(), consumeKafka.getTimeout(),
					resolveWorkerPool(consumeKafka.getWorkerPool()), Action.linkList(transform(globalContext, consumeKafka.getAction(), null)));
			addAction(list, kafkaConsumeAction, location);
			break;
		}
		case "file": {
			File file = (File) actionElement.getValue();
			addAction(list, new FileAction(globalContext.bindProperties(file.getDir())), location);
			break;
		}
		case "fileSystemWatch": {
			FileSystemWatch fileSystemWatch = (FileSystemWatch) actionElement.getValue();
			List<String> dirs = new ArrayList<>();
			for (String dir : fileSystemWatch.getDir()) {
				dirs.add(globalContext.bindProperties(dir));
			}
			FileSystemWatchAction fileSystemWatchAction = new FileSystemWatchAction(dirs, fileSystemWatch.getMove(), fileSystemWatch.getMoveOnError(), fileSystemWatch.getTimeout(),
					resolveWorkerPool(fileSystemWatch.getWorkerPool()), Action.linkList(transform(globalContext, fileSystemWatch.getAction(), null)));
			addAction(list, fileSystemWatchAction, location);
			break;
		}
		case "jdbcProcedure": {
			JdbcProcedure jdbcProcedure = (JdbcProcedure) actionElement.getValue();
			if (jdbcProcedure.getWorkerPool() != null) {
				addAction(list, new SpawnAction(resolveWorkerPool(jdbcProcedure.getWorkerPool()), true, jdbcProcedure.isJoin()), location);
			}
			org.eclipse.persistence.jaxb.dynamic.DynamicJAXBContext jaxbContext = null;
			if (jdbcProcedure.getSchemaURI() != null) {
				SchemaArtifact schemaArtifact = loadArtifact(jdbcProcedure.getSchemaURI());
				addReference(schemaArtifact);
				schemaArtifact.validate(globalContext);
				jaxbContext = schemaArtifact.getJAXBContext(resolveClassLoader(globalContext, jdbcProcedure.getClassLoader()));
			}
			addAction(list, new JDBCProcedureAction(globalContext, jdbcProcedure.getDataSource(), jdbcProcedure.getSql(), createJDBCParameters(jdbcProcedure.getIn()
					.getJdbcParameter()), createJDBCParameters(jdbcProcedure.getOut().getJdbcParameter()), jdbcProcedure.getMaxRows(), jdbcProcedure.getTimeout(), jaxbContext), location);
			break;
		}
		case "jdbc": {
			Jdbc jdbc = (Jdbc) actionElement.getValue();
			if (jdbc.getWorkerPool() != null) {
				addAction(list, new SpawnAction(resolveWorkerPool(jdbc.getWorkerPool()), true, jdbc.isJoin()), location);
			}
			addAction(list, new JDBCSQLAction(globalContext, jdbc.getDataSource(), jdbc.getSql(), createJDBCParameters(jdbc.getJdbcParameter()), jdbc.getMaxRows(), jdbc.getTimeout()), location);
			break;
		}
		case "setMessage": {
			SetMessage setMessage = (SetMessage) actionElement.getValue();
			java.lang.ClassLoader classLoader = resolveClassLoader(globalContext, setMessage.getClassLoader());
			SetMessageAction setMessageAction;
			if (setMessage.getBody() != null) {
				setMessageAction = new SetMessageAction(setMessage.isClearAll(), classLoader, setMessage.getBody().getValue(), setMessage.getBody().getJavaType(), setMessage.getBody().getMethod());
			} else {
				setMessageAction = new SetMessageAction(setMessage.isClearAll(), classLoader, null, null, null);
			}
			for (JAXBElement<HeaderOrVariable> jaxbElement : setMessage.getHeaderOrVariable()) {
				HeaderOrVariable hov = jaxbElement.getValue();
				setMessageAction.addAssignment(hov.getName(), jaxbElement.getName().getLocalPart().equals("header"), hov.getValue(), hov.getJavaType(), hov.getMethod());
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
			AssignAction assignAction = new AssignAction(assignments, assign.getBody(), createNsDecls(assign.getNsDecl()), assign.getBindName(), assign.getContextItem(), false);
			XQueryArtifact.validateXQuerySource(this, getConnection(), assignAction.getXQuery());
			addAction(list, assignAction, location);
			break;
		}
		case "xml2json": {
			Xml2Json xml2Json = (Xml2Json) actionElement.getValue();
			SchemaArtifact schemaArtifact = loadArtifact(xml2Json.getSchemaURI());
			addReference(schemaArtifact);
			schemaArtifact.validate(globalContext);
			addAction(list, new XML2JsonAction(schemaArtifact.getJAXBContext(null), xml2Json.getType(), xml2Json.isJsonIncludeRoot(), xml2Json.getNsDecl().isEmpty() ? null :
				Collections.inverseMap(createNsDecls(xml2Json.getNsDecl()), true), xml2Json.isValidate() ? schemaArtifact.getSchema() : null, xml2Json.isFormattedOutput()), location);
			break;
		}
		case "json2xml": {
			Json2Xml json2Xml = (Json2Xml) actionElement.getValue();
			SchemaArtifact schemaArtifact = loadArtifact(json2Xml.getSchemaURI());
			addReference(schemaArtifact);
			schemaArtifact.validate(globalContext);
			addAction(list, new Json2XMLAction(schemaArtifact.getJAXBContext(null), json2Xml.getType(), json2Xml.isJsonIncludeRoot(), json2Xml.isCaseInsensitive(), json2Xml.getXmlElement(), json2Xml.getNsDecl().isEmpty() ? null :
				Collections.inverseMap(createNsDecls(json2Xml.getNsDecl()), true), json2Xml.isValidate() ? schemaArtifact.getSchema() : null, json2Xml.isFormattedOutput()), location);
			break;
		}
		case "transform": {
			Transform transform = (Transform) actionElement.getValue();
			if (transform.getXqueryURI() != null) {
				XQueryArtifact xQueryArtifact = loadArtifact(transform.getXqueryURI());
				addReference(xQueryArtifact);
				xQueryArtifact.validate(globalContext);
				addAction(list, new TransformAction(XQuerySource.create(xQueryArtifact.getContentAsBytes()), xQueryArtifact.getParent().getURI(), transform.getContextItem()), location);
			} else if (transform.getXquery() != null) {
				XQuerySource xquery = XQuerySource.create(transform.getXquery());
				XQueryArtifact.validateXQuerySource(this, getConnection(), xquery);
				addAction(list, new TransformAction(xquery, getParent().getURI(), transform.getContextItem()), location);
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
			addAction(list, new XSLTAction(xsltArtifact.getTemplates(), applyXSLT.getParams()), location);
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
			boolean complexExpression = validate.getExpression() != "." && !validate.getExpression().equals("*");
			if (complexExpression || !USE_SAX_VALIDATION) {
				ValidateAction validateAction = new ValidateAction(schemaArtifact.getSchema(), validate.getExpression(), createNsDecls(validate.getNsDecl()), validate.getContextItem());
				if (complexExpression) {
					XQueryArtifact.validateXQuerySource(this, getConnection(), validateAction.getXQuery());
				}
				addAction(list, validateAction, location);
			} else {
				SAXValidationAction action = new SAXValidationAction(schemaArtifact.getSchema());
				addAction(list, action, location);
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
			ConditionalAction conditionalAction = new ConditionalAction(conditional.getExpression(), createNsDecls(conditional.getNsDecl()),
				conditional.getBindName(), conditional.getContextItem(), Action.linkList(transform(globalContext, conditional.getAction(), null)));
			XQueryArtifact.validateXQuerySource(this, getConnection(), conditionalAction.getXQuery());
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
			ForkAction forkAction = new ForkAction(resolveWorkerPool(fork.getWorkerPool()), fork.isCopyMessage(), Action.linkList(transform(globalContext,
				fork.getAction(), fork.getErrorHandler())));
			addAction(list, forkAction, location);
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
			BranchOnVariableAction branchOnVariableAction = new BranchOnVariableAction(branchOnVariable.getVariable(), branchOnVariable.isUseRegEx(), defaultAction, nullAction);
			for (BranchOnVariable.Branch branch : branchOnVariable.getBranch()) {
				branchOnVariableAction.addBranch(branch.getValue(), Action.linkList(transform(globalContext, branch.getAction(), null)));
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
			BranchOnPathAction branchOnPathAction = new BranchOnPathAction(branchOnPath.getBasePath(), defaultAction);
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
			addAction(list, new DumpAction(dump.isBinary()), location);
			break;
		case "admin":
			addAction(list, new AdminAction(), location);
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
			return null;
		}
	}

	private static Collection<Entry<String, String>> createNsDecls(List<NsDecl> nsDecls) {
		HashMap<String, String> result = new HashMap<>();
		for (NsDecl nsDecl : nsDecls) {
			String prefix = nsDecl.getPrefix();
			result.put(prefix != null ? prefix : "", nsDecl.getNamespace());
		}
		return result.entrySet();
	}

	private static List<JDBCParameter> createJDBCParameters(List<JdbcParameter> jdbcParameters) {
		List<JDBCParameter> params = new ArrayList<>(jdbcParameters.size());
		for (int i = 0; i < jdbcParameters.size();) {
			JdbcParameter jdbcParameter = jdbcParameters.get(i++);
			int pos = jdbcParameter.getPos() != null ? jdbcParameter.getPos() : i;
			params.add(new JDBCParameter(pos, jdbcParameter.getType(), jdbcParameter.isBody(), jdbcParameter.isAttachments(),
					jdbcParameter.getVariable(), jdbcParameter.getTruncate(), jdbcParameter.getXmlElement()));
		}
		return params;
	}

}
