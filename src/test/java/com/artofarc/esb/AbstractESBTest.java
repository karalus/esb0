package com.artofarc.esb;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Collections;

import org.junit.After;
import org.junit.Before;

import com.artofarc.esb.action.AssignAction;
import com.artofarc.esb.action.HttpOutboundAction;
import com.artofarc.esb.artifact.FileSystem;
import com.artofarc.esb.artifact.XMLCatalog;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.http.HttpEndpoint;
import com.artofarc.esb.http.HttpUrl;
import com.artofarc.util.StreamUtils;

public abstract class AbstractESBTest {

	protected Context context;

	@Before
	public void createContext() throws Exception {
		createContext(null);
	}

	protected void createContext(File dir) throws Exception {
		GlobalContext globalContext = new GlobalContext(null);
		globalContext.setFileSystem(new FileSystem(dir));
		XMLCatalog.attachToFileSystem(globalContext.getFileSystem());
		context = new Context(globalContext.getDefaultWorkerPool().getPoolContext());
	}

	@After
	public void closeContext() throws Exception {
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

	protected static AssignAction createAssignAction(String varName, String expression) {
		return new AssignAction(varName, expression, null, Collections.<String> emptyList(), null);
	}

}
