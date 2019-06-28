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

import java.io.IOException;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.util.StreamUtils;

public class JarArtifact extends Artifact {

	// will increase memory consumption opposed to reducing class loading time
	private static final boolean CACHE_JARS_UNZIPPED = Boolean.parseBoolean(System.getProperty("esb0.cacheJARsUnzipped"));

	private HashMap<String, byte[]> _entries = new HashMap<>();

	// track whether the JAR is used 
	boolean _used;

	public JarArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	@Override
	public String getContentType() {
		return "application/octet-stream";
	}

	@Override
	protected JarArtifact clone(FileSystem fileSystem, Directory parent) {
		JarArtifact clone = initClone(new JarArtifact(fileSystem, parent, getName()));
		clone._entries = _entries;
		return clone;
	}

	@Override
	protected void validateInternal(GlobalContext globalContext) throws IOException {
		try (ZipInputStream zis = new ZipInputStream(getContentAsStream())) {
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				if (!entry.isDirectory()) {
					_entries.put(entry.getName(), CACHE_JARS_UNZIPPED ? StreamUtils.copy(zis) : null);
				}
			}
		}
	}

	public final boolean contains(String filename) {
		return _entries.containsKey(filename);
	}

	public final byte[] getEntry(String filename) throws IOException {
		_used = true;
		if (!CACHE_JARS_UNZIPPED) {
			try (ZipInputStream zis = new ZipInputStream(getContentAsStream())) {
				ZipEntry entry;
				while ((entry = zis.getNextEntry()) != null) {
					if (entry.getName().equals(filename)) {
						return StreamUtils.copy(zis);
					}
				}
			}
		}
		return _entries.get(filename);
	}

	@Override
	protected void clearContent() {
		if (CACHE_JARS_UNZIPPED) {
			super.clearContent();
		}
	}

}
