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

public final class ESBServletContextListener implements ServletContextListener {

	public static final String ADMIN_SERVLET_PATH = "admin/deploy";
	public static final String CONTEXT = "esb0.context";

	public GlobalContext createContext(ClassLoader classLoader, String root, Properties manifest) {
		Properties properties = new Properties();
		properties.setProperty(GlobalContext.VERSION, manifest.getProperty("Implementation-Version", "0.0"));
		properties.setProperty(GlobalContext.BUILD_TIME, manifest.getProperty("Build-Time", ""));
		GlobalContext globalContext = new GlobalContext(classLoader, java.lang.management.ManagementFactory.getPlatformMBeanServer(), properties);
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
			DeployHelper.createAdminService(globalContext, '/' + ADMIN_SERVLET_PATH + "/*");
		} catch (ValidationException e) {
			globalContext.close();
			throw new RuntimeException("Could not validate artifact " + e.getArtifactLocation(), e.getCause());
		} catch (Exception e) {
			globalContext.close();
			throw new RuntimeException("Could not initialize services", e);
		}
		return globalContext;
	}

	@Override
	public void contextInitialized(ServletContextEvent contextEvent) {
		ServletContext servletContext = contextEvent.getServletContext();
		Properties manifest = new Properties();
		InputStream inputStream = servletContext.getResourceAsStream("/META-INF/MANIFEST.MF");
		if (inputStream != null) {
			try {
				manifest.load(inputStream);
				inputStream.close();
			} catch (IOException e) {
				// ignore
			}
		}
		servletContext.setAttribute(CONTEXT, createContext(servletContext.getClassLoader(), System.getProperty("esb0.root", System.getenv("ESB_ROOT_DIR")), manifest));
	}

	@Override
	public void contextDestroyed(ServletContextEvent contextEvent) {
		GlobalContext globalContext = (GlobalContext) contextEvent.getServletContext().getAttribute(ESBServletContextListener.CONTEXT);
		if (globalContext != null) {
			globalContext.close();
		}
	}

	public static void main(String[] args) {
		new ESBServletContextListener().createContext(ESBServletContextListener.class.getClassLoader(), args[0], new Properties());
	}

}
