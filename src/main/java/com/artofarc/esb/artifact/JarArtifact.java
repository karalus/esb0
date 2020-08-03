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
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.util.IOUtils;

public class JarArtifact extends Artifact {

	// will increase memory consumption opposed to reducing class loading time
	private static final boolean CACHE_JARS_UNZIPPED = Boolean.parseBoolean(System.getProperty("esb0.cacheJARsUnzipped"));

	private Jar _jar;

	public JarArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	final Jar getJar() {
		return _jar;
	}

	public final boolean isUsed() {
		return _jar._used;
	}

	@Override
	public String getContentType() {
		return "application/octet-stream";
	}

	@Override
	protected JarArtifact clone(FileSystem fileSystem, Directory parent) {
		JarArtifact clone = initClone(new JarArtifact(fileSystem, parent, getName()));
		clone._jar = _jar;
		return clone;
	}

	@Override
	protected void validateInternal(GlobalContext globalContext) throws Exception {
		_jar = new Jar(getContentAsBytes());
	}

	static final class Jar {

		private final byte[] _content;
		private final HashMap<String, byte[]> _entries = new HashMap<>();

		// track whether the JAR is used 
		volatile boolean _used;

		Jar(byte[] content) throws IOException {
			try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(content))) {
				ZipEntry entry;
				while ((entry = zis.getNextEntry()) != null) {
					if (!entry.isDirectory()) {
						_entries.put(entry.getName(), CACHE_JARS_UNZIPPED ? IOUtils.copy(zis) : null);
					}
				}
			}
			_content = CACHE_JARS_UNZIPPED ? null : content;
		}

		boolean contains(String filename) {
			return _entries.containsKey(filename);
		}

		byte[] getEntry(String filename) throws IOException {
			_used = true;
			if (!CACHE_JARS_UNZIPPED) {
				try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(_content))) {
					ZipEntry entry;
					while ((entry = zis.getNextEntry()) != null) {
						if (entry.getName().equals(filename)) {
							return IOUtils.copy(zis);
						}
					}
				}
			}
			return _entries.get(filename);
		}

		URL createUrlForEntry(String filename) {
			try {
				return new URL(null, new URI("esb0:/" + filename).toString(), new URLStreamHandler() {

					@Override
					protected URLConnection openConnection(URL u) {
						return new URLConnection(u) {

							@Override
							public void connect() {
							}

							@Override
							public InputStream getInputStream() throws IOException {
								return new ByteArrayInputStream(getEntry(url.getPath().substring(1)));
							}
						};
					}
				});
			} catch (URISyntaxException | MalformedURLException e) {
				throw new RuntimeException(e);
			}
		}
	}

}
