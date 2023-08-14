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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.zip.CRC32;

import com.artofarc.util.IOUtils;

public class FileSystemDir extends FileSystem {

	private final File _anchorDir;

	public FileSystemDir(File anchorDir) {
		_anchorDir = anchorDir;
	}

	protected FileSystemDir(FileSystemDir fileSystem) {
		super(fileSystem);
		_anchorDir = fileSystem._anchorDir;
	}

	@Override
	protected FileSystemDir copy() {
		return new FileSystemDir(this);
	}

	@Override
	protected InputStream createInputStream(String uri) throws FileNotFoundException {
		return new IOUtils.PredictableFileInputStream(new File(_anchorDir, uri));
	}

	@Override
	protected void load() throws IOException {
		readDir(getRoot(), _anchorDir, new CRC32());
	}

	private void readDir(Directory base, File dir, CRC32 crc) throws IOException {
		for (File file : dir.listFiles()) {
			String name = file.getName();
			// Ignore files starting with dot (important for Mac OSX)
			if (name.startsWith(".")) continue;
			if (file.isDirectory()) {
				readDir(new Directory(this, base, name), file, crc);
			} else {
				Artifact artifact = createArtifact(base, name);
				if (artifact != null) {
					artifact.setContent(IOUtils.readFile(file));
					artifact.setModificationTime(file.lastModified());
					crc.update(artifact.getContent());
					artifact.setCrc(crc.getValue());
					crc.reset();
				}
			}
		}
	}

	@Override
	protected void writeBackChanges(Map<String, ChangeType> changes) throws IOException {
		for (Map.Entry<String, ChangeType> entry : changes.entrySet()) {
			File file = new File(_anchorDir, entry.getKey());
			if (entry.getValue() == ChangeType.DELETE) {
				if (!file.delete() && file.exists()) {
					logger.error("Could not delete " + file);
				}
			} else {
				Artifact artifact = getArtifact(entry.getKey());
				if (artifact instanceof Directory) {
					file.mkdir();
				} else {
					FileOutputStream fos = new FileOutputStream(file);
					fos.write(artifact.getContent());
					fos.close();
				}
			}
		}
	}

}
