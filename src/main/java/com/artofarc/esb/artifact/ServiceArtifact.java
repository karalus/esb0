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

import javax.xml.bind.JAXBElement;

import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.TimerService;
import com.artofarc.esb.action.Action;
import com.artofarc.esb.action.AssignAction;
import com.artofarc.esb.action.AssignHeadersAction;
import com.artofarc.esb.action.BranchOnPathAction;
import com.artofarc.esb.action.BranchOnPathAction.PathTemplate;
import com.artofarc.esb.action.BranchOnVariableAction;
import com.artofarc.esb.action.ConditionalAction;
import com.artofarc.esb.action.DumpAction;
import com.artofarc.esb.action.ForkAction;
import com.artofarc.esb.action.HttpInboundAction;
import com.artofarc.esb.action.HttpOutboundAction;
import com.artofarc.esb.action.HttpServletResponseAction;
import com.artofarc.esb.action.JDBCProcedureAction;
import com.artofarc.esb.action.JDBCSQLAction;
import com.artofarc.esb.action.JMSAction;
import com.artofarc.esb.action.Json2XMLAction;
import com.artofarc.esb.action.ProcessJsonAction;
import com.artofarc.esb.action.KafkaConsumeAction;
import com.artofarc.esb.action.KafkaProduceAction;
import com.artofarc.esb.action.PostSOAPHttpAction;
import com.artofarc.esb.action.PreSOAPHttpAction;
import com.artofarc.esb.action.SetMessageAction;
import com.artofarc.esb.action.SpawnAction;
import com.artofarc.esb.action.TransformAction;
import com.artofarc.esb.action.UnwrapSOAPAction;
import com.artofarc.esb.action.ValidateAction;
import com.artofarc.esb.action.WrapSOAPAction;
import com.artofarc.esb.action.XML2JsonAction;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.http.HttpEndpoint;
import com.artofarc.esb.jdbc.JDBCParameter;
import com.artofarc.esb.jms.JMSConsumer;
import com.artofarc.esb.service.ActionBase;
import com.artofarc.esb.service.ActionPipeline;
import com.artofarc.esb.service.ActionPipelineRef;
import com.artofarc.esb.service.Assign;
import com.artofarc.esb.service.AssignHeaders;
import com.artofarc.esb.service.BranchOnPath;
import com.artofarc.esb.service.BranchOnVariable;
import com.artofarc.esb.service.Conditional;
import com.artofarc.esb.service.ConsumeKafka;
import com.artofarc.esb.service.Fork;
import com.artofarc.esb.service.Http;
import com.artofarc.esb.service.InternalService;
import com.artofarc.esb.service.Jdbc;
import com.artofarc.esb.service.JdbcParameter;
import com.artofarc.esb.service.JdbcProcedure;
import com.artofarc.esb.service.Jms;
import com.artofarc.esb.service.Json2Xml;
import com.artofarc.esb.service.NsDecl;
import com.artofarc.esb.service.PostSOAPHttp;
import com.artofarc.esb.service.PreSOAPHttp;
import com.artofarc.esb.service.ProcessJson;
import com.artofarc.esb.service.ProduceKafka;
import com.artofarc.esb.service.Property;
import com.artofarc.esb.service.Service;
import com.artofarc.esb.service.Service.HttpBindURI;
import com.artofarc.esb.service.Service.JmsBinding;
import com.artofarc.esb.service.Service.TimerBinding;
import com.artofarc.esb.service.SetMessage;
import com.artofarc.esb.service.Spawn;
import com.artofarc.esb.service.Transform;
import com.artofarc.esb.service.UnwrapSOAP;
import com.artofarc.esb.service.Validate;
import com.artofarc.esb.service.WrapSOAP;
import com.artofarc.esb.service.Xml2Json;
import com.artofarc.esb.servlet.HttpConsumer;
import com.artofarc.util.Collections;

public class ServiceArtifact extends AbstractServiceArtifact {

	public final static String FILE_EXTENSION = "xservice";

	private Service _service;
	private ConsumerPort _consumerPort;

	// only used during validation
	private final HashMap<String, List<Action>> _actionPipelines = new HashMap<>();

	public ServiceArtifact(Directory parent, String name) {
		super(parent, name);
	}

	public Service getService() {
		return _service;
	}

	@SuppressWarnings("unchecked")
	public <C extends ConsumerPort> C getConsumerPort() {
		return (C) _consumerPort;
	}

	@Override
	public ServiceArtifact clone(Directory parent) {
		ServiceArtifact clone = initClone(new ServiceArtifact(parent, getName()));
		clone._service = _service;
		return clone;
	}

	@Override
	protected void validateInternal(GlobalContext globalContext) throws Exception {
		_service = unmarshal();
		// parse
		for (ActionPipeline actionPipeline : _service.getActionPipeline()) {
			_actionPipelines.put(actionPipeline.getName(), transform(globalContext, actionPipeline.getAction(), actionPipeline.getErrorHandler()));
		}
		List<Action> list = transform(globalContext, _service.getAction(), _service.getErrorHandler());
		switch (_service.getProtocol()) {
		case HTTP:
			final HttpBindURI httpBinding = _service.getHttpBindURI();
			_consumerPort = new HttpConsumer(getURI(), httpBinding.getValue(), httpBinding.getMinPool(), httpBinding.getMaxPool(), httpBinding.getKeepAlive());
			_consumerPort.setTerminalAction(new HttpServletResponseAction(httpBinding.isSupportCompression()));
			break;
		case JMS:
			final JmsBinding jmsBinding = _service.getJmsBinding();
			_consumerPort = new JMSConsumer(getURI(), jmsBinding.getJndiConnectionFactory(), jmsBinding.getJndiDestination(), jmsBinding.getQueueName(),
					jmsBinding.getTopicName(), jmsBinding.getMessageSelector(), jmsBinding.getWorkerCount());
			break;
		case TIMER:
			final TimerBinding timerBinding = _service.getTimerBinding();
			_consumerPort = new TimerService(getURI(), resolveWorkerPool(timerBinding.getWorkerPool()), timerBinding.getInitialDelay(), timerBinding.getPeriod(), timerBinding.isFixedDelay());
			break;
		default:
			_consumerPort = new ConsumerPort(getURI());
			break;
		}
		_consumerPort.setInternalService(list);
		_consumerPort.setEnabled(_service.isEnabled());
		_actionPipelines.clear();
	}

	private List<Action> transform(GlobalContext globalContext, List<JAXBElement<? extends ActionBase>> actions, String errorHandler) throws Exception {
		List<Action> list = new ArrayList<>();
		for (JAXBElement<? extends ActionBase> jaxbElement : actions) {
			switch (jaxbElement.getName().getLocalPart()) {
			case "http": {
				Http http = (Http) jaxbElement.getValue();
				HttpEndpoint httpEndpoint = new HttpEndpoint(http.getConnectionTimeout(), http.getRetries(), http.getCheckAliveInterval(), http.getKeepAliveInterval(), getModificationTime());
				for (Http.Url url : http.getUrl()) {
					httpEndpoint.addUrl(url.getValue(), url.getWeight(), url.isActive());
				}
				//globalContext.getHttpEndpointRegistry().validate(httpEndpoint);
				list.add(new HttpOutboundAction(httpEndpoint, http.getReadTimeout(), http.getChunkLength()));
				if (http.getWorkerPool() != null) {
					list.add(new SpawnAction(http.getWorkerPool(), false));
				}
				list.add(new HttpInboundAction());
				break;
			}
			case "jms": {
				Jms jms = (Jms) jaxbElement.getValue();
				list.add(new JMSAction(globalContext, jms.getJndiConnectionFactory(), jms.getJndiDestination(), jms.getQueueName(), jms.getTopicName(), jms.isBytesMessage(), jms.getPriority(), jms.getTimeToLive()));
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
						resolveWorkerPool(consumeKafka.getWorkerPool()), Action.linkList(transform(globalContext, consumeKafka.getAction(), errorHandler)));
				list.add(kafkaConsumeAction);
				break;
			}
			case "jdbcProcedure": {
				JdbcProcedure jdbcProcedure = (JdbcProcedure) jaxbElement.getValue();
				if (jdbcProcedure.getWorkerPool() != null) {
					list.add(new SpawnAction(jdbcProcedure.getWorkerPool(), true));
				}
				list.add(new JDBCProcedureAction(jdbcProcedure.getDataSource(), jdbcProcedure.getSql(), createJDBCParameters(jdbcProcedure.getIn()
						.getJdbcParameter()), createJDBCParameters(jdbcProcedure.getOut().getJdbcParameter()), jdbcProcedure.getFetchSize()));
				break;
			}
			case "jdbc": {
				Jdbc jdbc = (Jdbc) jaxbElement.getValue();
				if (jdbc.getWorkerPool() != null) {
					list.add(new SpawnAction(jdbc.getWorkerPool(), true));
				}
				list.add(new JDBCSQLAction(jdbc.getDataSource(), jdbc.getSql(), createJDBCParameters(jdbc.getJdbcParameter()), jdbc.getFetchSize()));
				break;
			}
			case "setMessage": {
				SetMessage setMessage = (SetMessage) jaxbElement.getValue();
				ClassLoader classLoader = null;
				if (setMessage.getClassLoader() != null) {
					ClassLoaderArtifact classLoaderArtifact = getArtifact(setMessage.getClassLoader() + '.' + ClassLoaderArtifact.FILE_EXTENSION);
					if (classLoaderArtifact == null) {
						throw new FileNotFoundException(setMessage.getClassLoader());
					}
					addReference(classLoaderArtifact);
					classLoaderArtifact.validate(globalContext);
					classLoader = classLoaderArtifact.getFileSystemClassLoader();
				}
				SetMessageAction setMessageAction;
				if (setMessage.getBody() != null) {
					setMessageAction = new SetMessageAction(classLoader, setMessage.getBody().getValue(), setMessage.getBody().getJavaType(), setMessage.getBody().getMethod());
				} else {
					setMessageAction = new SetMessageAction(classLoader, null, null, null);
				}
				for (com.artofarc.esb.service.SetMessage.Header header : setMessage.getHeader()) {
					setMessageAction.addHeader(header.getName(), header.getValue(), header.getJavaType(), header.getMethod());
				}
				for (com.artofarc.esb.service.SetMessage.Variable variable : setMessage.getVariable()) {
					setMessageAction.addVariable(variable.getName(), variable.getValue(), variable.getJavaType(), variable.getMethod());
				}
				list.add(setMessageAction);
				break;
			}
			case "processJson": {
				ProcessJson processJson = (ProcessJson) jaxbElement.getValue();
				ProcessJsonAction processJsonAction = new ProcessJsonAction(processJson.getBody());
				for (com.artofarc.esb.service.ProcessJson.Header header : processJson.getHeader()) {
					processJsonAction.addHeader(header.getName(), header.getValue());
				}
				for (com.artofarc.esb.service.ProcessJson.Variable variable : processJson.getVariable()) {
					processJsonAction.addVariable(variable.getName(), variable.getValue());
				}
				list.add(processJsonAction);
				break;
			}
			case "assign": {
				Assign assign = (Assign) jaxbElement.getValue();
				List<Entry<String, String>> assignments = new ArrayList<>();
				for (com.artofarc.esb.service.Assign.Assignment assignment : assign.getAssignment()) {
					assignments.add(Collections.createEntry(assignment.getVariable(), assignment.getValue()));
				}
				list.add(new AssignAction(assignments, createNsDecls(assign.getNsDecl()).entrySet(), assign.getBindName(), assign.getContextItem()));
				break;
			}
			case "assignHeaders": {
				AssignHeaders assignHeaders = (AssignHeaders) jaxbElement.getValue();
				ArrayList<Entry<String, String>> assignments = new ArrayList<>();
				for (com.artofarc.esb.service.AssignHeaders.Assignment assignment : assignHeaders.getAssignment()) {
					assignments.add(Collections.createEntry(assignment.getHeader(), assignment.getValue()));
				}
				list.add(new AssignHeadersAction(assignments, createNsDecls(assignHeaders.getNsDecl()).entrySet(), assignHeaders.getBindName(), assignHeaders
						.getContextItem(), assignHeaders.isClearAll()));
				break;
			}
			case "xml2json": {
				Xml2Json xml2Json = (Xml2Json) jaxbElement.getValue();
				SchemaArtifact schemaArtifact = getArtifact(xml2Json.getSchemaURI());
				if (schemaArtifact == null) {
					throw new FileNotFoundException(xml2Json.getSchemaURI());
				}
				addReference(schemaArtifact);
				schemaArtifact.validate(globalContext);
				list.add(new XML2JsonAction(schemaArtifact.getJAXBContext(), Collections.inverseMap(createNsDecls(xml2Json.getNsDecl())),
						xml2Json.isValidate() ? schemaArtifact.getSchema() : null, xml2Json.isFormattedOutput()));
				break;
			}
			case "json2xml": {
				Json2Xml json2Xml = (Json2Xml) jaxbElement.getValue();
				SchemaArtifact schemaArtifact = getArtifact(json2Xml.getSchemaURI());
				if (schemaArtifact == null) {
					throw new FileNotFoundException(json2Xml.getSchemaURI());
				}
				addReference(schemaArtifact);
				schemaArtifact.validate(globalContext);
				list.add(new Json2XMLAction(schemaArtifact.getJAXBContext(), Collections.inverseMap(createNsDecls(json2Xml.getNsDecl())),
						json2Xml.isValidate() ? schemaArtifact.getSchema() : null, json2Xml.isFormattedOutput()));
				break;
			}
			case "transform": {
				Transform transform = (Transform) jaxbElement.getValue();
				String xquery = transform.getXquery();
				if (transform.getXqueryURI() != null) {
					XQueryArtifact xQueryArtifact = getArtifact(transform.getXqueryURI());
					if (xQueryArtifact == null) {
						throw new FileNotFoundException(transform.getXqueryURI());
					}
					addReference(xQueryArtifact);
					xQueryArtifact.validate(globalContext);
					xquery = xQueryArtifact.getXQuery();
				}
				TransformAction transformAction = new TransformAction(xquery);
				list.add(transformAction);
				break;
			}
			case "unwrapSOAP": {
				UnwrapSOAP unwrapSOAP11 = (UnwrapSOAP) jaxbElement.getValue();
				WSDLArtifact wsdlArtifact = getArtifact(unwrapSOAP11.getWsdlURI());
				if (wsdlArtifact == null) {
					throw new FileNotFoundException(unwrapSOAP11.getWsdlURI());
				}
				addReference(wsdlArtifact);
				wsdlArtifact.validate(globalContext);
				UnwrapSOAPAction unwrapSOAP11Action = new UnwrapSOAPAction(unwrapSOAP11.isSoap12(), unwrapSOAP11.isSinglePart(), wsdlArtifact.getDefinition(), unwrapSOAP11.getTransport(), unwrapSOAP11.getWsdlURI());
				list.add(unwrapSOAP11Action);
				break;
			}
			case "wrapSOAP":
				WrapSOAP wrapSOAP11 = (WrapSOAP) jaxbElement.getValue();
				list.add(new WrapSOAPAction(wrapSOAP11.isSoap12(), wrapSOAP11.isSinglePart()));
				break;
			case "preSOAPHttp": {
				PreSOAPHttp preSOAP11Http = (PreSOAPHttp) jaxbElement.getValue();
				WSDLArtifact wsdlArtifact = getArtifact(preSOAP11Http.getWsdlURI());
				if (wsdlArtifact == null) {
					throw new FileNotFoundException(preSOAP11Http.getWsdlURI());
				}
				addReference(wsdlArtifact);
				wsdlArtifact.validate(globalContext);
				PreSOAPHttpAction preSOAP11HttpAction = new PreSOAPHttpAction(preSOAP11Http.isSoap12(), preSOAP11Http.isSinglePart(), wsdlArtifact.getDefinition(), preSOAP11Http.getTransport());
				list.add(preSOAP11HttpAction);
				break;
			}
			case "postSOAPHttp":
				PostSOAPHttp postSOAP11Http = (PostSOAPHttp) jaxbElement.getValue();
				list.add(new PostSOAPHttpAction(postSOAP11Http.isSoap12(), postSOAP11Http.isSinglePart()));
				break;
			case "validate": {
				Validate validate = (Validate) jaxbElement.getValue();
				SchemaArtifact schemaArtifact = getArtifact(validate.getSchemaURI());
				if (schemaArtifact == null) {
					throw new FileNotFoundException(validate.getSchemaURI());
				}
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
				ServiceArtifact serviceArtifact = getArtifact(internalService.getServiceURI() + '.' + FILE_EXTENSION);
				if (serviceArtifact == null) {
					throw new FileNotFoundException(internalService.getServiceURI());
				}
				addReference(serviceArtifact);
				serviceArtifact.validate(globalContext);
				list.addAll(serviceArtifact.getConsumerPort().getInternalService());
				break;
			}
			case "conditional": {
				Conditional conditional = (Conditional) jaxbElement.getValue();
				ConditionalAction conditionalAction = new ConditionalAction(conditional.getExpression(), createNsDecls(conditional.getNsDecl()).entrySet(), conditional.getBindName(), conditional.getContextItem());
				conditionalAction.setConditionalAction(Action.linkList(transform(globalContext, conditional.getAction(), errorHandler)));
				list.add(conditionalAction);
				break;
			}
			case "spawn": {
				Spawn spawn = (Spawn) jaxbElement.getValue();
				list.add(new SpawnAction(resolveWorkerPool(spawn.getWorkerPool()), spawn.isUsePipe()));
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
					defaultAction = Action.linkList(transform(globalContext, branchOnVariable.getDefault().getAction(), errorHandler));
				}
				BranchOnVariableAction branchOnVariableAction = new BranchOnVariableAction(branchOnVariable.getVariable(), defaultAction);
				for (com.artofarc.esb.service.BranchOnVariable.Branch branch : branchOnVariable.getBranch()) {
					branchOnVariableAction.getBranchMap().put(branch.getValue(), Action.linkList(transform(globalContext, branch.getAction(), errorHandler)));
				}
				list.add(branchOnVariableAction);
				break;
			}
			case "branchOnPath": {
				BranchOnPath branchOnPath = (BranchOnPath) jaxbElement.getValue();
				Action defaultAction = null;
				if (branchOnPath.getDefault() != null) {
					defaultAction = Action.linkList(transform(globalContext, branchOnPath.getDefault().getAction(), errorHandler));
				}
				BranchOnPathAction branchOnPathAction = new BranchOnPathAction(branchOnPath.getBasePath(), defaultAction);
				for (com.artofarc.esb.service.BranchOnPath.Branch branch : branchOnPath.getBranch()) {
					branchOnPathAction.getBranchMap().put(new PathTemplate(branch.getPathTemplate()), Action.linkList(transform(globalContext, branch.getAction(), errorHandler)));
				}
				list.add(branchOnPathAction);
				break;
			}
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
			for (Action action : list) {
				action.setErrorHandler(errorHandlerPipeline);
			}
		}
		return list;
	}

	private String resolveWorkerPool(String workerPool) throws FileNotFoundException {
		if (workerPool != null) {
			WorkerPoolArtifact workerPoolArtifact = getArtifact(workerPool + '.' + WorkerPoolArtifact.FILE_EXTENSION);
			if (workerPoolArtifact == null) {
				throw new FileNotFoundException(workerPool);
			}
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

	private List<JDBCParameter> createJDBCParameters(List<JdbcParameter> jdbcParameters) throws NoSuchFieldException {
		List<JDBCParameter> params = new ArrayList<>();
		for (JdbcParameter jdbcParameter : jdbcParameters) {
			params.add(new JDBCParameter(jdbcParameter.getPos(), jdbcParameter.getType(), jdbcParameter.isBody(), jdbcParameter.getVariable(), jdbcParameter.getTruncate()));
		}
		return params;
	}

}
