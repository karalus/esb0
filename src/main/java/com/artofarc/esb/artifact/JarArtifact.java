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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.message.ESBMessage;

/**
 * Caches entry names to speed up search, but do not cache full entry because of memory consumption.
 */
public class JarArtifact extends Artifact {
	
	private HashSet<String> _entries = new HashSet<>();

	public JarArtifact(Directory parent, String name) {
		super(parent, name);
	}

	@Override
	protected JarArtifact clone(Directory parent) {
		JarArtifact clone = initClone(new JarArtifact(parent, getName()));
		clone._entries = _entries;
		return clone;
	}

	@Override
	public void validateInternal(GlobalContext globalContext) throws IOException {
      try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(getContent()))) {
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				if (!entry.isDirectory()) {
					_entries.add(entry.getName());
				}
			}
      }
	}
	
	public boolean contains(String filename) {
		return _entries.contains(filename);
	}
	
	public byte[] getEntry(String filename) throws IOException {
      try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(getContent()))) {
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				if (entry.getName().equals(filename)) {
					ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
					ESBMessage.copyStream(zis, bos);
					return bos.toByteArray();		
				}
			}
      }
      return null;
	}

}
