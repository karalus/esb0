package com.artofarc.esb;

import java.io.File;
import java.io.IOException;

import org.junit.After;
import org.junit.Before;

import com.artofarc.esb.artifact.FileSystem;
import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;

public abstract class AbstractESBTest {

	protected Context context;

	@Before
	public void createContext() throws Exception {
		GlobalContext globalContext = new GlobalContext(null);
		globalContext.setFileSystem(new FileSystem());
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

	protected static byte[] readFile(String fileName) throws IOException {
		return FileSystem.readFile(new File(fileName));
	}

}
