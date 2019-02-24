package com.artofarc.esb;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;

import org.junit.After;
import org.junit.Before;

import com.artofarc.esb.action.HttpOutboundAction;
import com.artofarc.esb.artifact.FileSystem;
import com.artofarc.esb.artifact.XMLCatalog;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.http.HttpEndpoint;

public abstract class AbstractESBTest {

	protected Context context;

	@Before
	public void createContext() throws Exception {
		createContext(null);
	}

	protected void createContext(File dir) throws Exception {
		GlobalContext globalContext = new GlobalContext(null);
		globalContext.setFileSystem(new FileSystem(dir));
		XMLCatalog.attachToFileSystem(globalContext);
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
		return FileSystem.readFile(new File(fileName));
	}

	public HttpOutboundAction createHttpOutboundAction(String url) throws MalformedURLException {
		return new HttpOutboundAction(new HttpEndpoint(null, 1000, 0, null, null, System.currentTimeMillis()).addUrl(url, 1, true), 60000, null);
	}
	
}
