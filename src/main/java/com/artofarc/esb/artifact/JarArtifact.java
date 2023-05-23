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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.util.IOUtils;

public class JarArtifact extends Artifact {

	private Jar _jar;

	public JarArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	final Jar getJar() {
		return _jar;
	}

	public final Map<String, ?> getEntries() {
		synchronized (_jar) {
			return _jar._entries;
		}
	}

	public final boolean isUsed() {
		return getEntries() != null;
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
	protected void validateInternal(GlobalContext globalContext) {
		_jar = new Jar(this);
	}

	@Override
	protected void invalidate() {
		_jar = null;
		super.invalidate();
	}

	static final class Jar {

		private final WeakReference<JarArtifact> _jarArtifact;
		private Map<String, WeakReference<byte[]>> _entries;
		private Manifest _manifest;

		Jar(JarArtifact jarArtifact) {
			_jarArtifact = new WeakReference<>(jarArtifact);
		}

		private void loadAll() throws IOException {
			JarArtifact jarArtifact = _jarArtifact.get();
			if (jarArtifact == null) {
				throw new IllegalStateException("JarArtifact is detached " + jarArtifact);
			}
			logger.info("Reading " + jarArtifact);
			_entries = new LinkedHashMap<>();
			try (JarInputStream jis = new JarInputStream(jarArtifact.getContentAsStream())) {
				JarEntry entry;
				while ((entry = jis.getNextJarEntry()) != null) {
					if (!entry.isDirectory()) {
						_entries.put(entry.getName(), new WeakReference<>(IOUtils.toByteArray(jis)));
					}
				}
				_manifest = jis.getManifest();
			} finally {
				jarArtifact.clearContent();
			}
		}

		private byte[] reload(String filename) throws IOException {
			JarArtifact jarArtifact = _jarArtifact.get();
			if (jarArtifact == null) {
				throw new IllegalStateException("JarArtifact is detached " + jarArtifact);
			}
			logger.info("Reloading " + jarArtifact);
			byte[] result = null;
			try (JarInputStream jis = new JarInputStream(jarArtifact.getContentAsStream())) {
				JarEntry entry;
				while ((entry = jis.getNextJarEntry()) != null) {
					WeakReference<byte[]> ref = _entries.get(entry.getName());
					if (ref != null && ref.get() == null) {
						byte[] data = IOUtils.toByteArray(jis);
						if (entry.getName().equals(filename)) {
							result = data;
						}
						_entries.replace(entry.getName(), new WeakReference<>(data));
					}
				}
			} finally {
				jarArtifact.clearContent();
			}
			return result;
		}

		Manifest getManifest() {
			return _manifest;
		}

		synchronized byte[] getEntry(String filename, boolean nullify) throws IOException {
			if (_entries == null) {
				loadAll();
			}
			WeakReference<byte[]> ref = _entries.get(filename);
			byte[] data = null;
			if (ref != null) {
				data = ref.get();
				if (data == null) {
					data = reload(filename);
				}
				if (nullify) {
					_entries.replace(filename, null);
				}
			} else if (nullify && _entries.containsKey(filename)) {
				throw new IllegalStateException("Same jar is loaded twice from different classLoaders " + _jarArtifact.get());
			}
			return data;
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
						public ByteArrayInputStream getInputStream() throws IOException {
							return new ByteArrayInputStream(data);
						}
					};
				}
			});
		}
	}

}
