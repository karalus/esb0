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
package com.artofarc.esb.servlet;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import com.artofarc.esb.artifact.FileSystem;
import com.artofarc.esb.artifact.FileSystem.ChangeSet;
import com.artofarc.esb.artifact.ValidationException;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.context.PoolContext;

public class ESBServletContextListener implements ServletContextListener, Runnable {

	public static final String POOL_CONTEXT = "WebContainerPoolContext";

	private GlobalContext globalContext;

	public final PoolContext createGlobalAndDefaultPoolContext(File rootDir) {
		if (!rootDir.exists() || !rootDir.isDirectory()) {
			throw new RuntimeException("No directory " + rootDir);
		}
		globalContext = new GlobalContext();
		FileSystem fileSystem = new FileSystem();
		globalContext.setFileSystem(fileSystem);
		PoolContext poolContext = new PoolContext(globalContext);
		try {
			ChangeSet changeSet = fileSystem.parseDirectory(globalContext, rootDir);
			DeployServlet.deployChangeSet(globalContext, poolContext, changeSet);
		} catch (IOException e) {
			throw new RuntimeException("Could not read services", e);
		} catch (ValidationException e) {
			throw new RuntimeException("Could not validate artifact " + e.getArtifact(), e.getCause());
		}
		globalContext.getWorkerPool(null).getScheduledExecutorService().scheduleAtFixedRate(this, 30L, 30L, TimeUnit.SECONDS);
		return poolContext;
	}

	@Override
	public void contextInitialized(ServletContextEvent contextEvent) {
		String rootDirEnv = System.getenv("ESB_ROOT_DIR");
		File rootDir = rootDirEnv != null ? new File(rootDirEnv) : new File(System.getProperty("user.home"), "esb_root");
		contextEvent.getServletContext().setAttribute(POOL_CONTEXT, createGlobalAndDefaultPoolContext(rootDir));
	}

	@Override
	public void contextDestroyed(ServletContextEvent contextEvent) {
		PoolContext poolContext = (PoolContext) contextEvent.getServletContext().getAttribute(POOL_CONTEXT);
		if (poolContext != null) {
			poolContext.close();
			poolContext.getGlobalContext().close();
		}
	}

	@Override
	public void run() {
		for (String path : globalContext.getHttpServicePaths()) {
			HttpConsumer consumerPort = globalContext.getHttpService(path);
			// check because it could be undeployed meanwhile
			if (consumerPort != null) {
				consumerPort.shrinkPool();
			}
		}

	}

}
