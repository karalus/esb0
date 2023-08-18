/*
 * Copyright 2021 Andre Karalus
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
package com.artofarc.esb.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import com.artofarc.esb.artifact.DeployHelper;
import com.artofarc.esb.context.GlobalContext;

public final class ESBServletContextListener implements ServletContextListener {

	public static final String ADMIN_SERVLET_PATH = "/admin/deploy";
	public static final String CONTEXT = "esb0.context";
	public static final String ESB_ROOT_DIR = "esb0.root";

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
		Properties properties = new Properties();
		properties.setProperty(GlobalContext.VERSION, manifest.getProperty("Implementation-Version", "0.0"));
		properties.setProperty(GlobalContext.BUILD_TIME, manifest.getProperty("Build-Time", ""));
		GlobalContext globalContext = new GlobalContext(servletContext.getClassLoader(), java.lang.management.ManagementFactory.getPlatformMBeanServer(), properties);
		String esbRootDir = System.getProperty("esb0.root", System.getenv("ESB_ROOT_DIR"));
		if (esbRootDir == null) {
			esbRootDir = System.getProperty("user.home") + "/esb_root";
		}
		try {
			DeployHelper.attachFileSystemAndDeploy(globalContext, esbRootDir);
			DeployHelper.createAdminService(globalContext, ADMIN_SERVLET_PATH);
			servletContext.setAttribute(CONTEXT, globalContext);
			servletContext.setAttribute(ESB_ROOT_DIR, esbRootDir);
		} catch (Exception e) {
			globalContext.close();
			throw new RuntimeException("Could not initialize services", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent contextEvent) {
		GlobalContext globalContext = (GlobalContext) contextEvent.getServletContext().getAttribute(ESBServletContextListener.CONTEXT);
		if (globalContext != null) {
			globalContext.close();
		}
	}

}
