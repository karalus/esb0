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
package com.artofarc.esb.artifact;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.SecureClassLoader;
import java.util.Enumeration;
import java.util.List;
import java.util.Vector;

public class FileSystemClassLoader extends SecureClassLoader {

	private final List<JarArtifact.Jar> _jars;

	public FileSystemClassLoader(List<JarArtifact.Jar> jars, ClassLoader parent) {
		super(parent);
		_jars = jars;
	}

	/**
	 * Order of classloading:
	 * <ol>
	 * <li>look in cache</li>
	 * <li>call findClass: look in jars</li>
	 * <li>propagate to parent classloader</li>
	 * </ol>
	 * 
	 * @see FileSystemClassLoader#findClass(String)
	 * @see ClassLoader#loadClass(String)
	 */
	@Override
	public synchronized Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
		// First, check if the class has already been loaded
		Class<?> result = findLoadedClass(name);
		if (result == null) {
			try {
				result = findClass(name); // invoke our own find algorithm
			} catch (Exception e) { // also catches java.lang.SecurityException
				result = getParent().loadClass(name); // invoke parent
			}
		}
		if (resolve) {
			resolveClass(result);
		}
		return result;
	}

	@Override
	protected Class<?> findClass(final String name) throws ClassNotFoundException {
		String classFilename = name.replace('.', '/') + ".class";
		try {
			// search only with our own mechanism here!
			byte[] classData = findInJarArtifacts(classFilename);
			if (classData != null) {
				// Looking up the package
				int pos = name.lastIndexOf('.');
				if (pos > 0) {
					String packageName = name.substring(0, pos);
					if (getPackage(packageName) == null) {
						try {
							definePackage(packageName, null, null, null, null, null, null, null);
						} catch (IllegalArgumentException e) {
							// Ignore: normal error due to dual definition of package
						}
					}
				}
				return defineClass(name, classData, 0, classData.length);
			}
		} catch (Exception e) {
			throw new ClassNotFoundException(name, e);
		}
		throw new ClassNotFoundException(name);
	}

	private byte[] findInJarArtifacts(final String filename) throws IOException {
		for (JarArtifact.Jar jar : _jars) {
			if (jar.contains(filename)) {
				return jar.getEntry(filename);
			}
		}
		return null;
	}

	private URL findURLInJarArtifacts(String filename) {
		for (JarArtifact.Jar jar : _jars) {
			if (jar.contains(filename)) {
				return jar.createUrlForEntry(filename);
			}
		}
		return null;
	}

	@Override
	public InputStream getResourceAsStream(String name) {
		try {
			byte[] data = findInJarArtifacts(name);
			if (data != null) {
				return new ByteArrayInputStream(data);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return super.getResourceAsStream(name);
	}

	@Override
	public URL getResource(String name) {
		URL url = findURLInJarArtifacts(name);
		if (url != null) {
			return url;
		}
		return super.getResource(name);
	}

	@Override
	public Enumeration<URL> getResources(String name) throws IOException {
		Enumeration<URL> resources = super.getResources(name);
		URL url = findURLInJarArtifacts(name);
		if (url != null) {
			Vector<URL> urls = new Vector<>();
			urls.add(url);
			while (resources.hasMoreElements()) {
				urls.add(resources.nextElement());
			}
			resources = urls.elements();
		}
		return resources;
	}

}
