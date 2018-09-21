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

import java.io.FileNotFoundException;
import java.util.ArrayList;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.service.ClassLoader;

public class ClassLoaderArtifact extends AbstractServiceArtifact {

	public final static String FILE_EXTENSION = "cldef";

	private FileSystemClassLoader _fileSystemClassLoader;

	public ClassLoaderArtifact(Directory parent, String name) {
		super(parent, name);
	}
	
	@Override
	public ClassLoaderArtifact clone(Directory parent) {
		ClassLoaderArtifact clone = initClone(new ClassLoaderArtifact(parent, getName()));
		clone._fileSystemClassLoader = _fileSystemClassLoader;
		return clone;
	}

	@Override
	protected void validateInternal(GlobalContext globalContext) throws Exception {
		ClassLoader classLoader = unmarshal();
		ArrayList<JarArtifact> jars = new ArrayList<>();
		for (String jar : classLoader.getJar()) {
			JarArtifact jarArtifact = getArtifact(jar);
			if (jarArtifact == null) {
				throw new FileNotFoundException(jar);
			}
			addReference(jarArtifact);
			jarArtifact.validate(globalContext);
			jars.add(jarArtifact);
		}
		// parent
		if (classLoader.getParent() != null) {
			ClassLoaderArtifact classLoaderArtifact = getArtifact(classLoader.getParent() + '.' + ClassLoaderArtifact.FILE_EXTENSION);
			if (classLoaderArtifact == null) {
				throw new FileNotFoundException(classLoader.getParent());
			}
			addReference(classLoaderArtifact);
			classLoaderArtifact.validate(globalContext);
			_fileSystemClassLoader = new FileSystemClassLoader(jars, classLoaderArtifact.getFileSystemClassLoader());
		} else {
			_fileSystemClassLoader = new FileSystemClassLoader(jars, getClass().getClassLoader());
		}
	}

	public FileSystemClassLoader getFileSystemClassLoader() {
		return _fileSystemClassLoader;
	}

}