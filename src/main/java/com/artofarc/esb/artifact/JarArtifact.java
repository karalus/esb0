/*
 * Copyright 2022 Andre Karalus
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
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.util.IOUtils;

public class JarArtifact extends Artifact {

	private Jar _jar;

	public JarArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	final Jar getJar() {
		return _jar;
	}

	public final Map<String, Jar.Entry> getEntries() {
		synchronized (_jar) {
			return _jar._entries;
		}
	}

	public final boolean isUsed() {
		return getEntries() != null;
	}

	@Override
	public String getContentType() {
		return HttpConstants.HTTP_HEADER_CONTENT_TYPE_OCTET_STREAM;
	}

	@Override
	protected JarArtifact clone(FileSystem fileSystem, Directory parent) {
		JarArtifact clone = initClone(new JarArtifact(fileSystem, parent, getName()));
		clone._jar = _jar;
		return clone;
	}

	@Override
	protected void validateInternal(GlobalContext globalContext) throws Exception {
		_jar = new Jar(globalContext, this);
	}

	void offerSacrifice() {
		if (_jar != null) {
			_jar.offerSacrifice();
		}
	}

	@Override
	protected void invalidate(Collection<Artifact> orphans) {
		_jar = null;
		super.invalidate(orphans);
	}

	void reload() throws IOException {
		logger.info("Reloading " + this);
		synchronized (_jar) {
			_jar.load(this);
		}
	}

	public static final class Jar {

		public static final class Entry {
			private byte[] data;
			private WeakReference<byte[]> ref;

			private Entry(byte[] data) {
				this.data = data;
			}

			public boolean isConsumed() {
				return data == null && ref == null;
			}
		}

		private final GlobalContext _globalContext;
		private final String _jarArtifactURI;
		private final Map<String, Entry> _entries = new LinkedHashMap<>();
		private final Manifest _manifest;

		Jar(GlobalContext globalContext, JarArtifact jarArtifact) throws IOException {
			_globalContext = globalContext;
			_jarArtifactURI = jarArtifact.getURI();
			_manifest = load(jarArtifact);
		}

		private Manifest load(JarArtifact jarArtifact) throws IOException {
			try (JarInputStream jis = new JarInputStream(jarArtifact.getContentAsStream())) {
				JarEntry jarEntry;
				while ((jarEntry = jis.getNextJarEntry()) != null) {
					if (!jarEntry.isDirectory()) {
						byte[] data = IOUtils.toByteArray(jis);
						Entry entry = _entries.putIfAbsent(jarEntry.getName(), new Entry(data));
						if (entry != null) {
							entry.data = data;
							entry.ref = null;
						}
					}
				}
				return jis.getManifest();
			}
		}

		private synchronized void offerSacrifice() {
			for (Entry entry : _entries.values()) {
				if (entry.data != null) {
					entry.ref = new WeakReference<>(entry.data);
					entry.data = null;
				}
			}
		}

		private byte[] reload(String filename) throws IOException {
			JarArtifact jarArtifact = _globalContext.getFileSystem().loadArtifact(_jarArtifactURI);
			logger.info("Reloading " + _jarArtifactURI);
			byte[] result = null;
			try (JarInputStream jis = new JarInputStream(jarArtifact.getContentAsStream())) {
				JarEntry jarEntry;
				while ((jarEntry = jis.getNextJarEntry()) != null) {
					if (!jarEntry.isDirectory()) {
						Entry entry = _entries.get(jarEntry.getName());
						if (entry.ref != null && entry.ref.get() == null) {
							byte[] data = IOUtils.toByteArray(jis);
							if (jarEntry.getName().equals(filename)) {
								result = data;
							} else {
								entry.ref = new WeakReference<>(data);
							}
						}
					}
				}
			}
			return result;
		}

		Manifest getManifest() {
			return _manifest;
		}

		byte[] getEntry(String filename, boolean nullify) throws IOException {
			Entry entry = _entries.get(filename);
			if (entry != null) {
				synchronized (this) {
					byte[] data = entry.data;
					if (entry.ref != null) {
						data = entry.ref.get();
						if (data == null) {
							data = reload(filename);
						}
						entry.ref = null;
					}
					if (data == null) {
						throw new NoClassDefFoundError(filename + " is loaded twice in different classLoaders from " + _jarArtifactURI);
					}
					entry.data = nullify ? null : data;
					return data;
				}
			}
			return null;
		}

		URL createUrlForEntry(String filename) throws IOException {
			byte[] data = getEntry(filename, false);
			return data == null ? null : new URL(null, "esb0:" + filename, new URLStreamHandler() {

				@Override
				protected URLConnection openConnection(URL u) {
					return new URLConnection(u) {

						@Override
						public void connect() {
						}

						@Override
						public ByteArrayInputStream getInputStream() {
							return new ByteArrayInputStream(data);
						}
					};
				}
			});
		}
	}

}
