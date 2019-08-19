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

import java.util.ArrayList;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.service.ClassLoader;

public class ClassLoaderArtifact extends AbstractServiceArtifact {

	public final static String FILE_EXTENSION = "cldef";

	private FileSystemClassLoader _fileSystemClassLoader;

	public ClassLoaderArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	@Override
	protected ClassLoaderArtifact clone(FileSystem fileSystem, Directory parent) {
		ClassLoaderArtifact clone = initClone(new ClassLoaderArtifact(fileSystem, parent, getName()));
		clone._fileSystemClassLoader = _fileSystemClassLoader;
		return clone;
	}

	@Override
	protected void validateInternal(GlobalContext globalContext) throws Exception {
		ClassLoader classLoader = unmarshal();
		ArrayList<JarArtifact.Jar> jars = new ArrayList<>();
		for (String jar : classLoader.getJar()) {
			JarArtifact jarArtifact = loadArtifact(jar);
			jarArtifact.validate(globalContext);
			addReference(jarArtifact);
			jars.add(jarArtifact.getJar());
		}
		// parent
		if (classLoader.getParent() != null) {
			ClassLoaderArtifact classLoaderArtifact = loadArtifact(classLoader.getParent() + '.' + FILE_EXTENSION);
			addReference(classLoaderArtifact);
			classLoaderArtifact.validate(globalContext);
			_fileSystemClassLoader = new FileSystemClassLoader(jars, classLoaderArtifact.getFileSystemClassLoader());
		} else {
			_fileSystemClassLoader = new FileSystemClassLoader(jars, getClass().getClassLoader());
		}
	}

	public final FileSystemClassLoader getFileSystemClassLoader() {
		return _fileSystemClassLoader;
	}

}
