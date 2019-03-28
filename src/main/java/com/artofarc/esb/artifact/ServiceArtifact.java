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
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;

import javax.wsdl.BindingOperation;
import javax.xml.bind.JAXBElement;

import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.TimerService;
import com.artofarc.esb.action.*;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.XQuerySource;
import com.artofarc.esb.http.HttpEndpoint;
import com.artofarc.esb.http.HttpUrl;
import com.artofarc.esb.jdbc.JDBCParameter;
import com.artofarc.esb.jms.JMSConsumer;
import com.artofarc.esb.service.*;
import com.artofarc.esb.servlet.HttpConsumer;
import com.artofarc.util.Collections;
import com.artofarc.util.WSDL4JUtil;

public class ServiceArtifact extends AbstractServiceArtifact {

	public final static String FILE_EXTENSION = "xservice";

	private Protocol _protocol;
	private ConsumerPort _consumerPort;

	// only used during validation
	private final HashMap<String, List<Action>> _actionPipelines = new HashMap<>();

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

	@Override
	protected void validateInternal(GlobalContext globalContext) throws Exception {
		Service service = unmarshal();
		for (ActionPipeline actionPipeline : service.getActionPipeline()) {
			_actionPipelines.put(actionPipeline.getName(), transform(globalContext, actionPipeline.getAction(), actionPipeline.getErrorHandler()));
		}
		List<Action> list = transform(globalContext, service.getAction(), service.getErrorHandler());
		switch (_protocol = service.getProtocol()) {
		case HTTP:
			final Service.HttpBindURI httpBinding = service.getHttpBindURI();
			_consumerPort = new HttpConsumer(getURI(), httpBinding.getValue(), httpBinding.getMinPool(), httpBinding.getMaxPool(), httpBinding.getKeepAlive(), httpBinding.isSupportCompression(), httpBinding.isMultipartResponse(), httpBinding.getBufferSize());
			break;
		case JMS:
			final Service.JmsBinding jmsBinding = service.getJmsBinding();
			_consumerPort = new JMSConsumer(globalContext, getURI(), jmsBinding.getWorkerPool(), jmsBinding.getJndiConnectionFactory(), jmsBinding.getJndiDestination(), jmsBinding.getQueueName(),
					jmsBinding.getTopicName(), jmsBinding.getSubscription(), jmsBinding.getMessageSelector(), jmsBinding.getWorkerCount(), jmsBinding.getPollInterval());
			break;
		case TIMER:
			final Service.TimerBinding timerBinding = service.getTimerBinding();
			_consumerPort = new TimerService(getURI(), resolveWorkerPool(timerBinding.getWorkerPool()), timerBinding.getTimeUnit(), timerBinding.getInitialDelay(), timerBinding.getPeriod(), timerBinding.isFixedDelay());
			break;
		default:
			_consumerPort = new ConsumerPort(getURI());
			break;
		}
		_consumerPort.setInternalService(list);
		_consumerPort.setEnabled(service.isEnabled());
		_actionPipelines.clear();
	}

	private List<Action> transform(GlobalContext globalContext, List<JAXBElement<? extends ActionBase>> actions, String errorHandler) throws Exception {
		List<Action> list = new ArrayList<>();
		for (JAXBElement<? extends ActionBase> jaxbElement : actions) {
			switch (jaxbElement.getName().getLocalPart()) {
			case "http": {
				Http http = (Http) jaxbElement.getValue();
				List<HttpUrl> endpoints = new ArrayList<>();
				for (Http.Url url : http.getUrl()) {
					endpoints.add(new HttpUrl(url.getValue(), url.getWeight(), url.isActive()));
				}
				HttpEndpoint httpEndpoint = new HttpEndpoint(http.getName(), endpoints, http.getConnectionTimeout(), http.getRetries(), http.getCheckAliveInterval(), http.getKeepAliveInterval(), getModificationTime());
				httpEndpoint = globalContext.getHttpEndpointRegistry().validate(httpEndpoint);
				list.add(new HttpOutboundAction(httpEndpoint, http.getReadTimeout(), http.getChunkLength()));
				if (http.getWorkerPool() != null) {
					list.add(new SpawnAction(resolveWorkerPool(http.getWorkerPool()), false, http.isJoin()));
				}
				list.add(new HttpInboundAction());
				break;
			}
			case "jms": {
				Jms jms = (Jms) jaxbElement.getValue();
				list.add(new JMSAction(globalContext, jms.getJndiConnectionFactory(), jms.getJndiDestination(), jms.getQueueName(), jms.getTopicName(), jms
						.isBytesMessage(), jms.getDeliveryMode(), jms.getPriority(), jms.getTimeToLive(), jms.isReceiveFromTempQueue()));
				break;
			}
			case "produceKafka": {
				ProduceKafka produceKafka = (ProduceKafka) jaxbElement.getValue();
				Properties properties = new Properties();
				for (Property property : produceKafka.getProperty()) {
					properties.put(property.getKey(), property.getValue());
				}
				list.add(new KafkaProduceAction(globalContext, properties, produceKafka.getTopic()));
				break;
			}
			case "consumeKafka": {
				ConsumeKafka consumeKafka = (ConsumeKafka) jaxbElement.getValue();
				Properties properties = new Properties();
				for (Property property : consumeKafka.getProperty()) {
					properties.put(property.getKey(), property.getValue());
				}
				KafkaConsumeAction kafkaConsumeAction = new KafkaConsumeAction(properties, consumeKafka.getTopic(), consumeKafka.getTimeout(),
						resolveWorkerPool(consumeKafka.getWorkerPool()), Action.linkList(transform(globalContext, consumeKafka.getAction(), null)));
				list.add(kafkaConsumeAction);
				break;
			}
			case "file": {
				File file = (File) jaxbElement.getValue();
				list.add(new FileAction(file.getDir()));
				break;
			}
			case "fileSystemWatch": {
				FileSystemWatch fileSystemWatch = (FileSystemWatch) jaxbElement.getValue();
				FileSystemWatchAction fileSystemWatchAction = new FileSystemWatchAction(fileSystemWatch.getDir(), fileSystemWatch.getTimeout(),
						resolveWorkerPool(fileSystemWatch.getWorkerPool()), Action.linkList(transform(globalContext, fileSystemWatch.getAction(), null)));
				list.add(fileSystemWatchAction);
				break;
			}
			case "jdbcProcedure": {
				JdbcProcedure jdbcProcedure = (JdbcProcedure) jaxbElement.getValue();
				if (jdbcProcedure.getWorkerPool() != null) {
					list.add(new SpawnAction(resolveWorkerPool(jdbcProcedure.getWorkerPool()), true, jdbcProcedure.isJoin()));
				}
				list.add(new JDBCProcedureAction(globalContext, jdbcProcedure.getDataSource(), jdbcProcedure.getSql(), createJDBCParameters(jdbcProcedure.getIn()
						.getJdbcParameter()), createJDBCParameters(jdbcProcedure.getOut().getJdbcParameter()), jdbcProcedure.getMaxRows(), jdbcProcedure.getTimeout()));
				break;
			}
			case "jdbc": {
				Jdbc jdbc = (Jdbc) jaxbElement.getValue();
				if (jdbc.getWorkerPool() != null) {
					list.add(new SpawnAction(resolveWorkerPool(jdbc.getWorkerPool()), true, jdbc.isJoin()));
				}
				list.add(new JDBCSQLAction(globalContext, jdbc.getDataSource(), jdbc.getSql(), createJDBCParameters(jdbc.getJdbcParameter()), jdbc.getMaxRows(), jdbc.getTimeout()));
				break;
			}
			case "setMessage": {
				SetMessage setMessage = (SetMessage) jaxbElement.getValue();
				java.lang.ClassLoader classLoader = null;
				if (setMessage.getClassLoader() != null) {
					ClassLoaderArtifact classLoaderArtifact = loadArtifact(setMessage.getClassLoader() + '.' + ClassLoaderArtifact.FILE_EXTENSION);
					addReference(classLoaderArtifact);
					classLoaderArtifact.validate(globalContext);
					classLoader = classLoaderArtifact.getFileSystemClassLoader();
				}
				SetMessageAction setMessageAction;
				if (setMessage.getBody() != null) {
					setMessageAction = new SetMessageAction(setMessage.isClearAll(), classLoader, setMessage.getBody().getValue(), setMessage.getBody().getJavaType(), setMessage.getBody().getMethod());
				} else {
					setMessageAction = new SetMessageAction(setMessage.isClearAll(), classLoader, null, null, null);
				}
				for (SetMessage.Header header : setMessage.getHeader()) {
					setMessageAction.addHeader(header.getName(), header.getValue(), header.getJavaType(), header.getMethod());
				}
				for (SetMessage.Variable variable : setMessage.getVariable()) {
					setMessageAction.addVariable(variable.getName(), variable.getValue(), variable.getJavaType(), variable.getMethod());
				}
				list.add(setMessageAction);
				break;
			}
			case "processJson": {
				ProcessJson processJson = (ProcessJson) jaxbElement.getValue();
				ProcessJsonAction processJsonAction = new ProcessJsonAction(processJson.getBody());
				for (ProcessJson.Header header : processJson.getHeader()) {
					processJsonAction.addHeader(header.getName(), header.getValue());
				}
				for (ProcessJson.Variable variable : processJson.getVariable()) {
					processJsonAction.addVariable(variable.getName(), variable.getValue());
				}
				list.add(processJsonAction);
				break;
			}
			case "assign": {
				Assign assign = (Assign) jaxbElement.getValue();
				List<Entry<String, String>> assignments = new ArrayList<>();
				for (Assign.Assignment assignment : assign.getAssignment()) {
					assignments.add(Collections.createEntry(assignment.getVariable(), assignment.getValue()));
				}
				list.add(new AssignAction(assignments, createNsDecls(assign.getNsDecl()).entrySet(), assign.getBindName(), assign.getContextItem()));
				break;
			}
			case "assignHeaders": {
				AssignHeaders assignHeaders = (AssignHeaders) jaxbElement.getValue();
				ArrayList<Entry<String, String>> assignments = new ArrayList<>();
				for (AssignHeaders.Assignment assignment : assignHeaders.getAssignment()) {
					assignments.add(Collections.createEntry(assignment.getHeader(), assignment.getValue()));
				}
				list.add(new AssignHeadersAction(assignments, createNsDecls(assignHeaders.getNsDecl()).entrySet(), assignHeaders.getBindName(), assignHeaders
						.getContextItem(), assignHeaders.isClearAll()));
				break;
			}
			case "xml2json": {
				Xml2Json xml2Json = (Xml2Json) jaxbElement.getValue();
				SchemaArtifact schemaArtifact = loadArtifact(xml2Json.getSchemaURI());
				addReference(schemaArtifact);
				schemaArtifact.validate(globalContext);
				list.add(new XML2JsonAction(schemaArtifact.getJAXBContext(), xml2Json.getType(), xml2Json.isJsonIncludeRoot(), xml2Json.getNsDecl().isEmpty() ? null :
					Collections.inverseMap(createNsDecls(xml2Json.getNsDecl()), true), xml2Json.isValidate() ? schemaArtifact.getSchema() : null, xml2Json.isFormattedOutput()));
				break;
			}
			case "json2xml": {
				Json2Xml json2Xml = (Json2Xml) jaxbElement.getValue();
				SchemaArtifact schemaArtifact = loadArtifact(json2Xml.getSchemaURI());
				addReference(schemaArtifact);
				schemaArtifact.validate(globalContext);
				list.add(new Json2XMLAction(schemaArtifact.getJAXBContext(), json2Xml.getType(), json2Xml.isJsonIncludeRoot(), json2Xml.getXmlElement(), json2Xml.getNsDecl().isEmpty() ? null :
					Collections.inverseMap(createNsDecls(json2Xml.getNsDecl()), true), json2Xml.isValidate() ? schemaArtifact.getSchema() : null, json2Xml.isFormattedOutput()));
				break;
			}
			case "transform": {
				Transform transform = (Transform) jaxbElement.getValue();
				if (transform.getXqueryURI() != null) {
					XQueryArtifact xQueryArtifact = loadArtifact(transform.getXqueryURI());
					addReference(xQueryArtifact);
					xQueryArtifact.validate(globalContext);
					list.add(new TransformAction(new XQuerySource(xQueryArtifact.getContentAsBytes()), xQueryArtifact.getParent().getURI()));
				} else if (transform.getXquery() != null) {
					XQuerySource xquery = new XQuerySource(transform.getXquery());
					XQueryArtifact.validateXQuerySource(this, xquery);
					list.add(new TransformAction(xquery, getParent().getURI()));
				} else {
					throw new ValidationException(this, "transform has no XQuery");
				}
				break;
			}
			case "applyXSLT": {
				ApplyXSLT applyXSLT = (ApplyXSLT) jaxbElement.getValue();
				XSLTArtifact xsltArtifact = loadArtifact(applyXSLT.getXslURI());
				addReference(xsltArtifact);
				xsltArtifact.validate(globalContext);
				list.add(new XSLTAction(xsltArtifact.getTemplates()));
				break;
			}
			case "unwrapSOAP": {
				UnwrapSOAP unwrapSOAP11 = (UnwrapSOAP) jaxbElement.getValue();
				WSDLArtifact wsdlArtifact = loadArtifact(unwrapSOAP11.getWsdlURI());
				addReference(wsdlArtifact);
				wsdlArtifact.validate(globalContext);
				List<BindingOperation> bindingOperations = WSDL4JUtil.getBindingOperations(wsdlArtifact.getAllBindings(), unwrapSOAP11.getBinding(), unwrapSOAP11.getTransport());
				UnwrapSOAPAction unwrapSOAP11Action = new UnwrapSOAPAction(unwrapSOAP11.isSoap12(), unwrapSOAP11.isSinglePart(), wsdlArtifact.getSchema(), bindingOperations, wsdlArtifact.getURI(), unwrapSOAP11.isGetWsdl());
				list.add(unwrapSOAP11Action);
				break;
			}
			case "wrapSOAP":
				WrapSOAP wrapSOAP11 = (WrapSOAP) jaxbElement.getValue();
				list.add(new WrapSOAPAction(wrapSOAP11.isSoap12(), wrapSOAP11.isHeader(), wrapSOAP11.isSinglePart()));
				break;
			case "preSOAPHttp": {
				PreSOAPHttp preSOAP11Http = (PreSOAPHttp) jaxbElement.getValue();
				WSDLArtifact wsdlArtifact = loadArtifact(preSOAP11Http.getWsdlURI());
				addReference(wsdlArtifact);
				wsdlArtifact.validate(globalContext);
				List<BindingOperation> bindingOperations = WSDL4JUtil.getBindingOperations(wsdlArtifact.getAllBindings(), preSOAP11Http.getBinding(), preSOAP11Http.getTransport());
				PreSOAPHttpAction preSOAP11HttpAction = new PreSOAPHttpAction(preSOAP11Http.isSoap12(), preSOAP11Http.isHeader(), preSOAP11Http.isSinglePart(), wsdlArtifact.getSchema(), bindingOperations);
				list.add(preSOAP11HttpAction);
				break;
			}
			case "postSOAPHttp":
				PostSOAPHttp postSOAP11Http = (PostSOAPHttp) jaxbElement.getValue();
				list.add(new PostSOAPHttpAction(postSOAP11Http.isSoap12(), postSOAP11Http.isSinglePart()));
				break;
			case "validate": {
				Validate validate = (Validate) jaxbElement.getValue();
				SchemaArtifact schemaArtifact = loadArtifact(validate.getSchemaURI());
				addReference(schemaArtifact);
				schemaArtifact.validate(globalContext);
				list.add(new ValidateAction(schemaArtifact.getSchema(), validate.getExpression(), createNsDecls(validate.getNsDecl()).entrySet()));
				break;
			}
			case "actionPipelineRef": {
				ActionPipelineRef actionPipelineRef = (ActionPipelineRef) jaxbElement.getValue();
				List<Action> actionPipeline = _actionPipelines.get(actionPipelineRef.getRef());
				if (actionPipeline == null) {
					throw new ValidationException(this, "actionPipeline not found: " + actionPipelineRef.getRef());
				}
				list.addAll(Action.cloneService(actionPipeline));
				break;
			}
			case "internalService": {
				InternalService internalService = (InternalService) jaxbElement.getValue();
				ServiceArtifact serviceArtifact = loadArtifact(internalService.getServiceURI() + '.' + FILE_EXTENSION);
				addReference(serviceArtifact);
				serviceArtifact.validate(globalContext);
				list.addAll(serviceArtifact.getConsumerPort().getInternalService());
				break;
			}
			case "conditional": {
				Conditional conditional = (Conditional) jaxbElement.getValue();
				ConditionalAction conditionalAction = new ConditionalAction(conditional.getExpression(), createNsDecls(conditional.getNsDecl()).entrySet(), conditional.getBindName(), conditional.getContextItem());
				conditionalAction.setConditionalAction(Action.linkList(transform(globalContext, conditional.getAction(), null)));
				list.add(conditionalAction);
				break;
			}
			case "spawn": {
				Spawn spawn = (Spawn) jaxbElement.getValue();
				list.add(new SpawnAction(resolveWorkerPool(spawn.getWorkerPool()), spawn.isUsePipe(), spawn.isJoin()));
				break;
			}
			case "fork": {
				Fork fork = (Fork) jaxbElement.getValue();
				ForkAction forkAction = new ForkAction(resolveWorkerPool(fork.getWorkerPool()), fork.isCopyMessage());
				forkAction.setFork(Action.linkList(transform(globalContext, fork.getAction(), fork.getErrorHandler())));
				list.add(forkAction);
				break;
			}
			case "branchOnVariable": {
				BranchOnVariable branchOnVariable = (BranchOnVariable) jaxbElement.getValue();
				Action defaultAction = null;
				if (branchOnVariable.getDefault() != null) {
					defaultAction = Action.linkList(transform(globalContext, branchOnVariable.getDefault().getAction(), null));
				}
				Action nullAction = null;
				if (branchOnVariable.getNull() != null) {
					nullAction = Action.linkList(transform(globalContext, branchOnVariable.getNull().getAction(), null));
				}
				BranchOnVariableAction branchOnVariableAction = new BranchOnVariableAction(branchOnVariable.getVariable(), defaultAction, nullAction);
				for (BranchOnVariable.Branch branch : branchOnVariable.getBranch()) {
					branchOnVariableAction.getBranchMap().put(branch.getValue(), Action.linkList(transform(globalContext, branch.getAction(), null)));
				}
				list.add(branchOnVariableAction);
				break;
			}
			case "branchOnPath": {
				BranchOnPath branchOnPath = (BranchOnPath) jaxbElement.getValue();
				Action defaultAction = null;
				if (branchOnPath.getDefault() != null) {
					defaultAction = Action.linkList(transform(globalContext, branchOnPath.getDefault().getAction(), null));
				}
				BranchOnPathAction branchOnPathAction = new BranchOnPathAction(branchOnPath.getBasePath(), defaultAction);
				for (BranchOnPath.Branch branch : branchOnPath.getBranch()) {
					branchOnPathAction.getBranchMap().put(new BranchOnPathAction.PathTemplate(branch.getPathTemplate()), Action.linkList(transform(globalContext, branch.getAction(), null)));
				}
				list.add(branchOnPathAction);
				break;
			}
			case "deserializeMtomXop":
				list.add(new MtomXopDeserializeAction());
				break;
			case "suspend":
				Suspend suspend = (Suspend) jaxbElement.getValue();
				list.add(new SuspendAction(suspend.getCorrelationID(), suspend.getTimeout()));
				break;
			case "resume":
				Resume resume = (Resume) jaxbElement.getValue();
				list.add(new ResumeAction(resume.getWorkerPool(), resume.getCorrelationID()));
				break;
			case "throwException":
				ThrowException throwException = (ThrowException) jaxbElement.getValue();
				list.add(new ThrowExceptionAction(throwException.getMessage()));
				break;
			case "dump":
				list.add(new DumpAction());
				break;
			default:
				throw new IllegalArgumentException("Cannot interpret " + jaxbElement.getName().getLocalPart());
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

	private String resolveWorkerPool(String workerPool) throws FileNotFoundException {
		if (workerPool != null) {
			WorkerPoolArtifact workerPoolArtifact = loadArtifact(workerPool + '.' + WorkerPoolArtifact.FILE_EXTENSION);
			addReference(workerPoolArtifact);
			return WorkerPoolArtifact.stripExt(workerPoolArtifact.getURI());
		} else {
			return null;
		}
	}

	private HashMap<String, String> createNsDecls(List<NsDecl> nsDecls) {
		HashMap<String, String> result = new HashMap<>();
		for (NsDecl nsDecl : nsDecls) {
			String prefix = nsDecl.getPrefix();
			result.put(prefix != null ? prefix : "", nsDecl.getNamespace());
		}
		return result;
	}

	private List<JDBCParameter> createJDBCParameters(List<JdbcParameter> jdbcParameters) {
		List<JDBCParameter> params = new ArrayList<>(jdbcParameters.size());
		for (int i = 0; i < jdbcParameters.size();) {
			JdbcParameter jdbcParameter = jdbcParameters.get(i++);
			int pos = jdbcParameter.getPos() != null ? jdbcParameter.getPos() : i;
			params.add(new JDBCParameter(pos, jdbcParameter.getType(), jdbcParameter.isBody(), jdbcParameter.getVariable(), jdbcParameter.getTruncate()));
		}
		return params;
	}

}
