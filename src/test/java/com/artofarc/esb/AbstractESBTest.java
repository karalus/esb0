package com.artofarc.esb;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.After;
import org.junit.Before;

import com.artofarc.esb.action.Action;
import com.artofarc.esb.action.AssignAction;
import com.artofarc.esb.action.ExecutionException;
import com.artofarc.esb.action.HttpOutboundAction;
import com.artofarc.esb.action.SAXValidationAction;
import com.artofarc.esb.action.TransformAction;
import com.artofarc.esb.action.UnwrapSOAPAction;
import com.artofarc.esb.action.ValidateAction;
import com.artofarc.esb.artifact.FileSystem;
import com.artofarc.esb.artifact.FileSystemDir;
import com.artofarc.esb.artifact.SchemaArtifact;
import com.artofarc.esb.artifact.ValidationException;
import com.artofarc.esb.artifact.XMLCatalog;
import com.artofarc.esb.artifact.XQueryArtifact;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.XQuerySource;
import com.artofarc.esb.http.HttpEndpoint;
import com.artofarc.esb.http.HttpUrl;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.service.XQDecl;
import com.artofarc.util.IOUtils;

public abstract class AbstractESBTest {

	private static final boolean USE_SAX_VALIDATION = Boolean.parseBoolean(System.getProperty("esb0.useSAXValidation"));

	protected Context context;

	@Before
	public void createContext() throws Exception {
		createContext(null);
	}

	protected void createContext(File dir) throws ValidationException {
		System.setProperty("java.naming.factory.initial", "org.apache.activemq.jndi.ActiveMQInitialContextFactory");
		System.setProperty("java.naming.provider.url", "vm://localhost");
		System.setProperty("esb0.httpconsumer.idletimeout", "0");
		GlobalContext globalContext = new GlobalContext(getClass().getClassLoader(), null, new Properties());
		globalContext.setFileSystem(dir != null ? new FileSystemDir(dir) : new FileSystem());
		XMLCatalog.attachToFileSystem(globalContext);
		context = new Context(globalContext.getDefaultWorkerPool().getPoolContext());
	}

	@After
	public void closeContext() {
		if (context != null) {
			context.close();
			context.getPoolContext().close();
			context.getPoolContext().getGlobalContext().close();
		}
	}

	protected GlobalContext getGlobalContext() {
		return context.getPoolContext().getGlobalContext();
	}

	protected static byte[] readFile(String fileName) throws IOException {
		return IOUtils.readFile(new File(fileName));
	}

	protected static HttpOutboundAction createHttpOutboundAction(String url) throws MalformedURLException {
		return new HttpOutboundAction(new HttpEndpoint(null, Collections.singletonList(new HttpUrl(url, 1, true)), null, null, 1000, 0, null, null, System.currentTimeMillis()), 60000, null, null);
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

	protected static AssignAction createAssignAction(List<AssignAction.Assignment> assignments, Map<String, String> namespaces, String... bindNames) {
		List<XQDecl> decls = new ArrayList<>();
		for (String bindName : bindNames) {
			XQDecl decl = new XQDecl();
			decl.setValue(bindName);
			decls.add(decl);
		}
		return new AssignAction(assignments, ".", namespaces != null ? namespaces.entrySet() : null, decls, null, false);
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
			assignments.add(new AssignAction.Assignment(varName, false, null, false, null));
		}
		return new TransformAction(XQuerySource.create(xqueryArtifact.getContent()), assignments, xqueryArtifact.getParent().getURI(), null);
	}

}
