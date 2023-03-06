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
package com.artofarc.esb;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.artofarc.esb.action.*;
import com.artofarc.esb.artifact.*;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.http.HttpEndpoint;
import com.artofarc.esb.http.HttpUrl;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.service.XQDecl;
import com.artofarc.util.IOUtils;
import com.artofarc.util.XQuerySource;

public abstract class AbstractESBTest {

	protected final static Logger logger = LoggerFactory.getLogger("com.artofarc.esb.junit");

	protected static boolean USE_SAX_VALIDATION = Boolean.parseBoolean(System.getProperty("esb0.useSAXValidation"));

	@BeforeClass
	public static void init() {
		System.setProperty("esb0.cacheXSGrammars", "false");
	}

	@AfterClass
	public static void destroy() {
		System.setProperty("esb0.cacheXSGrammars", "true");
	}

	protected Context context;

	@SuppressWarnings("resource")
	protected final void _createContext() {
		if (context == null) {
			context = new Context(new GlobalContext(getClass().getClassLoader(), null, new Properties()).getDefaultWorkerPool().getPoolContext());
		}
	}

	protected final void createContext(File dir) {
		_createContext();
		getGlobalContext().setFileSystem(new FileSystemDir(dir));
		XMLCatalog.attachToFileSystem(getGlobalContext());
	}

	@Before
	public void createContext() throws Exception {
		_createContext();
		getGlobalContext().setFileSystem(new FileSystem() {

			@Override
			public FileSystem copy() {
				return null;
			}

			@Override
			public void load() {
			}

			@Override
			public void writeBackChanges() {
			}
		});
		XMLCatalog.attachToFileSystem(getGlobalContext());
	}

	@After
	public void closeContext() {
		if (context != null) {
			context.close();
			getGlobalContext().close();
			context = null;
		}
	}

	protected final GlobalContext getGlobalContext() {
		return context.getPoolContext().getGlobalContext();
	}

	public final void transform(Source source, Result result) throws TransformerException {
		context.transform(source, result, null);
	}

	protected static byte[] readFile(String fileName) throws IOException {
		return IOUtils.readFile(new File(fileName));
	}

	protected static HttpOutboundAction createHttpOutboundAction(String url) throws Exception {
		return new HttpOutboundAction(new HttpEndpoint(null, Collections.singletonList(new HttpUrl(url, 1, true)), null, null, 1000, 0, null, null, System.currentTimeMillis(), Proxy.NO_PROXY, null), 60000, null, null, null);
	}

	protected static HttpAction createHttpAction(String... urls) throws Exception {
		List<HttpUrl> list = Arrays.stream(urls).map((url) -> {
			try {
				return new HttpUrl(url, 1, true);
			} catch (MalformedURLException e) {
				throw new RuntimeException(e);
			}
		}).collect(Collectors.toList());
		return new HttpAction(new HttpEndpoint(null, list, null, null, 1000, list.size() - 1, null, null, System.currentTimeMillis(), Proxy.NO_PROXY, null), 60000, null, null, null);
	}

	protected static AssignAction createAssignAction(String varName, String expression, String... bindNames) {
		List<XQDecl> decls = new ArrayList<>();
		for (String bindName : bindNames) {
			XQDecl decl = new XQDecl();
			decl.setValue(bindName);
			decls.add(decl);
		}
		return new AssignAction(varName, expression, null, decls, null);
	}

	protected static AssignAction createAssignAction(List<AssignAction.Assignment> assignments, String expression, Map<String, String> namespaces, String... bindNames) {
		List<XQDecl> decls = new ArrayList<>();
		for (String bindName : bindNames) {
			XQDecl decl = new XQDecl();
			decl.setValue(bindName);
			decls.add(decl);
		}
		return new AssignAction(assignments, true, expression, namespaces != null ? namespaces.entrySet() : null, decls, null, false);
	}

	protected static List<AssignAction.Assignment> createAssignments(boolean header, String... tuples) {
		List<AssignAction.Assignment> assignments = new ArrayList<>();
		for (int i = 0; i < tuples.length; ++i) {
			String varName = tuples[i];
			String expression = tuples[++i];
			assignments.add(new AssignAction.Assignment(varName, header, expression, false, null));
		}
		return assignments;
	}

	protected static Action createValidateAction(SchemaArtifact schemaArtifact) {
		return USE_SAX_VALIDATION ? new SAXValidationAction(schemaArtifact.getSchema()) : new ValidateAction(schemaArtifact.getSchema(), ".", null, null);
	}

	protected static UnwrapSOAPAction createUnwrapSOAPAction(boolean soap12, boolean singlePart) {
		return new UnwrapSOAPAction(soap12, singlePart) {

			@Override
			protected String determineOperation(ESBMessage message) throws ExecutionException {
				if (_operations == null) {
					return null;
				}
				return super.determineOperation(message);
			}
			
		};
	}

	protected static TransformAction createTransformAction(XQueryArtifact xqueryArtifact, String... varNames) {
		List<AssignAction.Assignment> assignments = new ArrayList<>();
		for (String varName : varNames) {
			assignments.add(new AssignAction.Assignment(varName, false));
		}
		return new TransformAction(XQuerySource.create(xqueryArtifact.getContent()), null, assignments, false, true, xqueryArtifact.getParent().getURI(), null);
	}

}
