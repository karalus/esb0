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

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.StringTokenizer;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.message.ESBMessage;

public final class FileSystem {

   protected final static Logger logger = Logger.getLogger("ESB");
   
	private final Directory _root;

	private File _anchorDir;
	private boolean _dirty;

	private FileSystem(Directory root) {
		_root = root;
	}

	public FileSystem() {
		this(new Directory(null, ""));
	}

	public Directory getRoot() {
		return _root;
	}

	public File getAnchorDir() {
		return _anchorDir;
	}

	public boolean isDirty() {
		return _dirty;
	}

	public <A extends Artifact> A getArtifact(String uri) {
		return getArtifact(_root, uri);
	}

	@SuppressWarnings("unchecked")
	protected static final <A extends Artifact> A getArtifact(Directory current, String uri) {
		if (uri == null)
			return (A) current;
		int i = 0, j;
		while ((j = uri.indexOf('/', i)) >= 0) {
			String dir = uri.substring(i, j);
			switch (dir) {
			case "":
				if (current.getParent() != null) {
					current = current.getRootDirectory();
				}
				break;
			case ".":
				break;
			case "..":
				current = current.getParent();
				break;
			default:
				current = (Directory) current.getArtifacts().get(dir);
				break;
			}
			if (current == null) {
				return null;
			}
			i = j + 1;
		}
		return (A) current.getArtifacts().get(uri.substring(i));
	}

	public ChangeSet parseDirectory(GlobalContext globalContext, File rootDir) throws IOException, ValidationException {
		readDir(_root, rootDir);
		_anchorDir = rootDir;
		ChangeSet services = new ChangeSet();
		validateServices(globalContext, _root, services);
		return services;
	}

	private final void validateServices(GlobalContext globalContext, Artifact artifact, ChangeSet changeSet) throws ValidationException {
		if (artifact instanceof Directory) {
			for (Artifact artifact2 : ((Directory) artifact).getArtifacts().values()) {
				validateServices(globalContext, artifact2, changeSet);
			}
		} else if (artifact instanceof ServiceArtifact) {
			artifact.validate(globalContext);
			changeSet.add((ServiceArtifact) artifact);
		} else if (artifact instanceof WorkerPoolArtifact) {
			artifact.validate(globalContext);
			changeSet.getWorkerPoolArtifacts().add((WorkerPoolArtifact) artifact);
		}
	}

	protected static final byte[] readFile(final File file) throws IOException {
		final byte[] ba = new byte[(int) file.length()];
		try (final DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
			dis.readFully(ba);
		}
		return ba;
	}

	protected static final void readDir(Directory base, File dir) throws IOException {
		for (File file : dir.listFiles()) {
			String name = file.getName();
			if (file.isDirectory()) {
				readDir(new Directory(base, name), file);
			} else {
				int i = name.lastIndexOf('.');
				String extension = i < 0 ? "" : name.substring(i + 1);
				Artifact artifact = createArtifact(base, name, extension);
				if (artifact != null) {
					artifact.setContent(readFile(file));
					artifact.setModificationTime(file.lastModified());
				}
			}
		}
	}

	protected static final void writeDir(Directory base, File dir) throws IOException {
		boolean prepared;
		if (!dir.exists()) {
			prepared = dir.mkdirs();
		} else {
			prepared = dir.isDirectory();
		}
		if (!prepared) {
			throw new IOException("Could not provide directory " + dir);
		}
		for (Entry<String, Artifact> entry : base.getArtifacts().entrySet()) {
			File file = new File(dir, entry.getKey());
			if (entry.getValue() instanceof Directory) {
				writeDir((Directory) entry.getValue(), file);
			} else {
				FileOutputStream fos = new FileOutputStream(file);
				fos.write(entry.getValue().getContent());
				fos.close();
			}
		}
	}
	
	public void writeDir(File dir) throws IOException {
		writeDir(_root, dir);
		_anchorDir = dir;
	}

	protected static final Artifact createArtifact(Directory parent, String name, String extension) {
		// Mac OSX
		if (name.startsWith("._"))
			return null;
		switch (extension) {
		case ServiceArtifact.FILE_EXTENSION:
			return new ServiceArtifact(parent, name);
		case "xsd":
			return new XSDArtifact(parent, name);
		case "wsdl":
			return new WSDLArtifact(parent, name);
		case "xq":
		case "xqy":
			return new XQueryArtifact(parent, name);
		case WorkerPoolArtifact.FILE_EXTENSION:
			return new WorkerPoolArtifact(parent, name);
		default:
			logger.fine("Cannot be imported: " + name);
			return null;
		}
	}

	protected Directory makeDirectory(String path) {
		Directory result = _root;
		String[] split = path.split("/");
		for (int i = 0; i < split.length; i++) {
			String name = split[i];
			Artifact artifact = result.getArtifacts().get(name);
			if (artifact == null) {
				result = new Directory(result, name);
			} else if (artifact instanceof Directory) {
				result = (Directory) artifact;
			} else {
				throw new IllegalStateException("Is not a directory: " + artifact);
			}
		}
		return result;
	}

	@Override
	public FileSystem clone() {
		return new FileSystem(_root.clone(null));
	}

	public final boolean deleteArtifact(Artifact artifact) {
		boolean deleted = false;
		if (artifact != null && artifact.getReferencedBy().isEmpty()) {
			for (String referenced : artifact.getReferenced()) {
				Artifact referencedArtifact = getArtifact(referenced);
				if (!referencedArtifact.getReferencedBy().remove(artifact.getURI())) {
					throw new IllegalStateException("References not consistent");
				}
			}
			deleted = artifact == artifact.getParent().getArtifacts().remove(artifact.getName());
			if (deleted && _anchorDir != null) {
				new File(_anchorDir, artifact.getURI()).delete();
			}
		}
		return deleted;
	}

	public boolean deleteArtifact(String uri) {
		return deleteArtifact(getArtifact(uri));
	}

	public boolean tidyOut() {
		HashSet<String> visited = new HashSet<>();
		collectFolders(visited, _root);
		return tidyOut(_root, visited);
	}

	private static final boolean tidyOut(Directory directory, HashSet<String> visited) {
		for (Iterator<Artifact> iterator = directory.getArtifacts().values().iterator(); iterator.hasNext();) {
			Artifact artifact = iterator.next();
			if (artifact instanceof Directory) {
				if (tidyOut((Directory) artifact, visited)) {
					logger.info("Remove: " + artifact.getURI());
					iterator.remove();
				}
			} else if (!visited.contains(artifact.getURI())) {
				logger.info("Remove: " + artifact);
				iterator.remove();
			}
		}
		return directory.getArtifacts().isEmpty();
	}

	private final void collectFolders(HashSet<String> visited, Directory directory) {
		for (Artifact artifact : directory.getArtifacts().values()) {
			if (artifact instanceof ServiceArtifact) {
				collectDownwardDependencies(visited, artifact.getURI());
			} else if (artifact instanceof Directory) {
				collectFolders(visited, (Directory) artifact);
			}
		}
	}

	private final void collectDownwardDependencies(HashSet<String> visited, String artifactUri) {
		if (visited.add(artifactUri)) {
			Artifact artifact = getArtifact(artifactUri);
			for (String referenced : artifact.getReferenced()) {
				collectDownwardDependencies(visited, referenced);
			}
		}
	}

	private final void collectUpwardDependencies(HashSet<String> visited, String artifactUri) {
		if (visited.add(artifactUri)) {
			Artifact artifact = getArtifact(artifactUri);
			for (String referenced : artifact.getReferencedBy()) {
				collectUpwardDependencies(visited, referenced);
			}
		}
	}

	public class ChangeSet extends ArrayList<ServiceArtifact> {
		private static final long serialVersionUID = 1L;

		private final ArrayList<WorkerPoolArtifact> workerPoolArtifacts = new ArrayList<>();

		public FileSystem getFileSystem() {
			return FileSystem.this;
		}

		public ArrayList<WorkerPoolArtifact> getWorkerPoolArtifacts() {
			return workerPoolArtifacts;
		}
	}

	public ChangeSet createUpdate(GlobalContext globalContext, InputStream InputStream) throws IOException, ValidationException {
		FileSystem clone = clone();
		Map<String, Artifact> changes = clone.mergeZIP(InputStream);
		ChangeSet changeSet = validateChanges(globalContext, clone, changes);
		clone._dirty = true;
		return changeSet;
	}

	protected ChangeSet validateChanges(GlobalContext globalContext, FileSystem fileSystem, Map<String, Artifact> changes) throws ValidationException {
		ChangeSet services = fileSystem.new ChangeSet();
		HashSet<String> visited = new HashSet<>();
		// find affected
		for (Entry<String, Artifact> entry : changes.entrySet()) {
			if (entry.getValue() == null) {
				collectUpwardDependencies(visited, entry.getKey());
			}
		}
		// invalidate
		for (String original : visited) {
			Artifact artifact = fileSystem.getArtifact(original);
			artifact.setValidated(false);
		}
		// validate
		for (String original : visited) {
			Artifact artifact = fileSystem.getArtifact(original);
			fileSystem.validateServices(globalContext, artifact, services);
		}
		for (Entry<String, Artifact> entry : changes.entrySet()) {
			if (entry.getValue() != null) {
				fileSystem.validateServices(globalContext, entry.getValue(), services);
			}
		}
		return services;
	}

	private Map<String, Artifact> mergeZIP(InputStream InputStream) throws IOException {
		HashMap<String, Artifact> changes = new HashMap<>();
		try (JarInputStream zis = new JarInputStream(InputStream)) {
			Manifest manifest = zis.getManifest();
			if (manifest != null) {
				String delete = manifest.getMainAttributes().getValue("delete");
				if (delete != null) {
					StringTokenizer tokenizer = new StringTokenizer(delete, " ");
					while (tokenizer.hasMoreTokens()) {
						String uri = tokenizer.nextToken();
						if (!deleteArtifact(uri)) {
							throw new IllegalArgumentException("Could not delete " + uri);
						}
					}
				}
			}
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				if (!entry.isDirectory()) {
					int i = entry.getName().lastIndexOf('/');
					Directory dir = i < 0 ? _root : makeDirectory(entry.getName().substring(0, i));
					String name = i < 0 ? entry.getName() : entry.getName().substring(i + 1);
					i = name.lastIndexOf('.');
					String extension = i < 0 ? "" : name.substring(i + 1);
					Artifact old = getArtifact(entry.getName());
					Artifact artifact = createArtifact(dir, name, extension);
					if (artifact != null) {
						ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
						ESBMessage.copyStream(zis, bos);
						artifact.setContent(bos.toByteArray());
						artifact.setModificationTime(entry.getTime());
						if (old != null) {
							if (!Arrays.equals(old.getContent(), artifact.getContent())) {
								// value == null means update
								changes.put(entry.getName(), null);
							}
						} else {
							changes.put(entry.getName(), artifact);
						}
					}
				}
			}
		}
		return changes;
	}

	public void writeZIP(File file) throws IOException {
		try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(file))) {

		}
	}

}
