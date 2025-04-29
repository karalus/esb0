/*
 * Copyright 2023 Andre Karalus
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
package com.artofarc.esb.artifact;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.SecureClassLoader;
import java.util.Enumeration;
import java.util.List;
import java.util.Vector;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

class FileSystemClassLoader extends SecureClassLoader {

	private final List<JarArtifact.Jar> _jars;

	FileSystemClassLoader(List<JarArtifact.Jar> jars, ClassLoader parent) {
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
			} catch (ClassNotFoundException | SecurityException e) {
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
			for (JarArtifact.Jar jar : _jars) {
				byte[] classData = jar.getEntry(classFilename, true);
				if (classData != null) {
					// Looking up the package
					int pos = name.lastIndexOf('.');
					if (pos > 0) {
						String packageName = name.substring(0, pos);
						if (getDefinedPackage(packageName) == null) {
							String specTitle = null, specVersion = null, specVendor = null;
							String implTitle = null, implVersion = null, implVendor = null;
							Manifest manifest = jar.getManifest();
							if (manifest != null) {
								Attributes attr = manifest.getMainAttributes();
								specTitle = attr.getValue(Attributes.Name.SPECIFICATION_TITLE);
								specVersion = attr.getValue(Attributes.Name.SPECIFICATION_VERSION);
								specVendor = attr.getValue(Attributes.Name.SPECIFICATION_VENDOR);
								implTitle = attr.getValue(Attributes.Name.IMPLEMENTATION_TITLE);
								implVersion = attr.getValue(Attributes.Name.IMPLEMENTATION_VERSION);
								implVendor = attr.getValue(Attributes.Name.IMPLEMENTATION_VENDOR);
							}
							try {
								definePackage(packageName, specTitle, specVersion, specVendor, implTitle, implVersion, implVendor, null);
							} catch (IllegalArgumentException e) {
								// Ignore: normal error due to dual definition of package
							}
						}
					}
					return defineClass(name, classData, 0, classData.length);
				}
			}
		} catch (IOException e) {
			throw (ClassFormatError) new ClassFormatError(name).initCause(e);
		}
		throw new ClassNotFoundException(name);
	}

	@Override
	public InputStream getResourceAsStream(String name) {
		try {
			for (JarArtifact.Jar jar : _jars) {
				byte[] data = jar.getEntry(name, false);
				if (data != null) {
					return new ByteArrayInputStream(data);
				}
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return super.getResourceAsStream(name);
	}

	@Override
	public URL getResource(String name) {
		try {
			for (JarArtifact.Jar jar : _jars) {
				URL url = jar.createUrlForEntry(name);
				if (url != null) {
					return url;
				}
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return super.getResource(name);
	}

	@Override
	public Enumeration<URL> getResources(String name) throws IOException {
		Vector<URL> urls = new Vector<>();
		for (JarArtifact.Jar jar : _jars) {
			URL url = jar.createUrlForEntry(name);
			if (url != null) {
				urls.add(url);
			}
		}
		Enumeration<URL> resources = super.getResources(name);
		while (resources.hasMoreElements()) {
			urls.add(resources.nextElement());
		}
		return urls.elements();
	}

}
