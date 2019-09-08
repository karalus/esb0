package com.artofarc.esb;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;

import com.artofarc.esb.action.AssignAction;
import com.artofarc.esb.action.ExecutionException;
import com.artofarc.esb.action.HttpOutboundAction;
import com.artofarc.esb.action.TransformAction;
import com.artofarc.esb.action.UnwrapSOAPAction;
import com.artofarc.esb.action.ValidateAction;
import com.artofarc.esb.artifact.FileSystem;
import com.artofarc.esb.artifact.FileSystemDir;
import com.artofarc.esb.artifact.SchemaArtifact;
import com.artofarc.esb.artifact.XMLCatalog;
import com.artofarc.esb.artifact.XQueryArtifact;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.XQuerySource;
import com.artofarc.esb.http.HttpEndpoint;
import com.artofarc.esb.http.HttpUrl;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.StreamUtils;

public abstract class AbstractESBTest {

	protected Context context;

	@Before
	public void createContext() throws Exception {
		createContext(null);
	}

	protected void createContext(File dir) {
		GlobalContext globalContext = new GlobalContext(null);
		globalContext.setFileSystem(dir != null ? new FileSystemDir(dir) : new FileSystem());
		XMLCatalog.attachToFileSystem(globalContext.getFileSystem());
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
		return StreamUtils.readFile(new File(fileName));
	}

	protected static HttpOutboundAction createHttpOutboundAction(String url) throws MalformedURLException {
		return new HttpOutboundAction(new HttpEndpoint(null, Collections.singletonList(new HttpUrl(url, 1, true)), 1000, 0, null, null, System.currentTimeMillis()), 60000, null);
	}

	protected static AssignAction createAssignAction(String varName, String expression, String... bindNames) {
		return new AssignAction(varName, expression, null, Arrays.asList(bindNames), null);
	}

	protected static AssignAction createAssignAction(Map<Map.Entry<String, Boolean>, String> assignments, Map<String, String> namespaces, String... bindNames) {
		return new AssignAction(assignments.entrySet(), namespaces != null ? namespaces.entrySet() : null, Arrays.asList(bindNames), null);
	}

	protected static Map<Map.Entry<String, Boolean>, String> createAssignments(String... tuples) {
		Map<Map.Entry<String, Boolean>, String> map = new LinkedHashMap<>();
		for (int i = 0; i < tuples.length; ++i) {
			String varName = tuples[i];
			String expression = tuples[++i];
			map.put(com.artofarc.util.Collections.createEntry(varName, false), expression);
		}
		return map;
	}

	protected static ValidateAction createValidateAction(SchemaArtifact schemaArtifact) {
		return new ValidateAction(schemaArtifact.getSchema(), ".", null, null);
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
		HashMap<String, Boolean> map = new HashMap<>();
		for (String varName : varNames) {
			map.put(varName, false);
		}
		return new TransformAction(XQuerySource.create(xqueryArtifact.getContent()), map.entrySet(), xqueryArtifact.getParent().getURI(), null);
	}

}
