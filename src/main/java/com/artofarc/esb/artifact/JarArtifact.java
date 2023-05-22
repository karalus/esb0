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
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

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

		private byte[] load(String filename) throws IOException {
			if (filename != null || _entries == null) {
				JarArtifact jarArtifact = _jarArtifact.get();
				if (jarArtifact == null) {
					throw new IllegalStateException("JarArtifact is detached " + jarArtifact);
				}
				logger.info("Reading " + jarArtifact);
				if (_entries == null) {
					_entries = new LinkedHashMap<>();
				}
				try (JarInputStream jis = new JarInputStream(jarArtifact.getContentAsStream())) {
					ZipEntry entry;
					while ((entry = jis.getNextEntry()) != null) {
						if (!entry.isDirectory() && (filename == null || _entries.containsKey(entry.getName()))) {
							byte[] data = IOUtils.toByteArray(jis);
							if (entry.getName().equals(filename)) {
								return data;
							}
							_entries.put(entry.getName(), new WeakReference<>(data));
						}
					}
					_manifest = jis.getManifest();
				} finally {
					jarArtifact.clearContent();
				}
			}
			return null;
		}

		Manifest getManifest() {
			return _manifest;
		}

		synchronized byte[] getEntry(String filename, boolean nullify) throws IOException {
			load(null);
			WeakReference<byte[]> ref = _entries.get(filename);
			byte[] data = null;
			if (ref != null) {
				data = ref.get();
				if (data == null) {
					data = load(filename);
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
