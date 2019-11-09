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
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import com.artofarc.esb.artifact.DeployHelper;
import com.artofarc.esb.artifact.FileSystem;
import com.artofarc.esb.artifact.FileSystemDB;
import com.artofarc.esb.artifact.FileSystemDir;
import com.artofarc.esb.artifact.ValidationException;
import com.artofarc.esb.artifact.XMLCatalog;
import com.artofarc.esb.context.GlobalContext;

public final class ESBServletContextListener implements ServletContextListener, Runnable {

	public static final String ADMIN_SERVLET_PATH = "/admin/deploy";

	public static final String VERSION = "esb0.version";
	public static final String BUILD_TIME = "esb0.build.time";
	public static final String CONTEXT = "esb0.context";
	public static final String ROOT = "esb0.root";

	private GlobalContext globalContext;

	public GlobalContext createContext(String root) {
		globalContext = new GlobalContext(java.lang.management.ManagementFactory.getPlatformMBeanServer());
		try {
			FileSystem fileSystem;
			if (root != null && root.contains("jdbc")) {
				fileSystem = new FileSystemDB(globalContext.<javax.sql.DataSource> lookup(root));
			} else {
				File rootDir = root != null ? new File(root) : new File(System.getProperty("user.home"), "esb_root");
				if (!rootDir.exists() || !rootDir.isDirectory()) {
					throw new IOException("No directory " + rootDir);
				}
				fileSystem = new FileSystemDir(rootDir);
			}
			globalContext.setFileSystem(fileSystem);
			XMLCatalog.attachToFileSystem(fileSystem);
			FileSystem.ChangeSet changeSet = fileSystem.init(globalContext);
			DeployHelper.deployChangeSet(globalContext, changeSet);
			DeployHelper.createAdminService(globalContext, ADMIN_SERVLET_PATH + '*');
		} catch (ValidationException e) {
			globalContext.close();
			throw new RuntimeException("Could not validate artifact " + e.getArtifactLocation(), e.getCause());
		} catch (Exception e) {
			globalContext.close();
			throw new RuntimeException("Could not initialize services", e);
		}
		globalContext.getDefaultWorkerPool().getScheduledExecutorService().scheduleAtFixedRate(this, 60L, 60L, TimeUnit.SECONDS);
		return globalContext;
	}

	@Override
	public void contextInitialized(ServletContextEvent contextEvent) {
		ServletContext servletContext = contextEvent.getServletContext();
		Properties properties = new Properties();
		InputStream inputStream = servletContext.getResourceAsStream("/META-INF/MANIFEST.MF");
		if (inputStream != null) {
			try {
				properties.load(inputStream);
				inputStream.close();
			} catch (IOException e) {
				// ignore
			}
		}
		servletContext.setAttribute(VERSION, properties.getProperty("Implementation-Version", "0.0"));
		servletContext.setAttribute(BUILD_TIME, properties.getProperty("Build-Time", ""));
		servletContext.setAttribute(CONTEXT, createContext(System.getProperty(ROOT, System.getenv("ESB_ROOT_DIR"))));
	}

	@Override
	public void contextDestroyed(ServletContextEvent contextEvent) {
		globalContext.close();
	}

	@Override
	public void run() {
		for (String path : globalContext.getHttpServicePaths()) {
			HttpConsumer consumerPort = globalContext.getHttpService(path);
			// check because it could be undeployed meanwhile
			if (consumerPort != null) {
				consumerPort.getContextPool().shrinkPool();
			}
		}
	}

	public static void main(String[] args) {
		new ESBServletContextListener().createContext(args[0]);
	}

}
