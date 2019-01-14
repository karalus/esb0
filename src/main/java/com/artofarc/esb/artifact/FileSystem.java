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

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.message.ESBMessage;

import java.io.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;

public final class FileSystem {

	protected final static Logger logger = Logger.getLogger("ESB");

	private final Directory _root;
	private final Map<String, ChangeType> _changes = new LinkedHashMap<>();

	private File _anchorDir;

	private FileSystem(Directory root) {
		_root = root;
	}

	public FileSystem() {
		this(new Directory(null, ""));
	}

	public Directory getRoot() {
		return _root;
	}
	
	protected Map<String, ChangeType> getChanges() {
		return _changes;
	}

	public File getAnchorDir() {
		return _anchorDir;
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
				Artifact artifact = current.getArtifacts().get(dir);
				current = artifact instanceof Directory ? (Directory) artifact : null;
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

	private static void validateServices(final GlobalContext globalContext, Artifact artifact, ChangeSet changeSet) throws ValidationException {
		if (artifact instanceof Directory) {
			for (Artifact artifact2 : ((Directory) artifact).getArtifacts().values()) {
				validateServices(globalContext, artifact2, changeSet);
			}
		} else if (artifact instanceof ServiceArtifact) {
			final ServiceArtifact serviceArtifact = (ServiceArtifact) artifact;
			Callable<ServiceArtifact> task = new Callable<ServiceArtifact>() {

				@Override
				public ServiceArtifact call() throws ValidationException {
					serviceArtifact.validate(globalContext);
					return serviceArtifact;
				}
				
			};
			Future<ServiceArtifact> future = globalContext.getDefaultWorkerPool().getExecutorService().submit(task);
			changeSet.futures.add(future);
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
				Artifact artifact = createArtifact(base, name);
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
	
	protected static final Artifact createArtifact(Directory parent, String name) {
		// Mac OSX
		if (name.startsWith("._"))
			return null;
		switch (Artifact.getExt(name)) {
		case ServiceArtifact.FILE_EXTENSION:
			return new ServiceArtifact(parent, name);
		case "xml":
			return new XMLArtifact(parent, name);
		case "xsd":
			return new XSDArtifact(parent, name);
		case "wsdl":
			return new WSDLArtifact(parent, name);
		case "xq":
		case "xqm":
		case "xqy":
			return new XQueryArtifact(parent, name);
		case WorkerPoolArtifact.FILE_EXTENSION:
			return new WorkerPoolArtifact(parent, name);
		case ClassLoaderArtifact.FILE_EXTENSION:
			return new ClassLoaderArtifact(parent, name);
		case "jar":
			return new JarArtifact(parent, name);
		default:
			logger.fine("Cannot be imported: " + name);
			return null;
		}
	}

	protected Directory makeDirectory(String path) {
		Directory result = _root;
		String[] split = path.split("/");
		for (String name : split) {
			Artifact artifact = result.getArtifacts().get(name);
			if (artifact == null) {
				result = new Directory(result, name);
				_changes.put(result.getURI(), ChangeType.CREATE);
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

	private boolean deleteArtifact(Artifact artifact) {
		boolean deleted = false;
		if (artifact != null && artifact.getReferencedBy().isEmpty()) {
			for (String referenced : artifact.getReferenced()) {
				Artifact referencedArtifact = getArtifact(referenced);
				if (!referencedArtifact.getReferencedBy().remove(artifact.getURI())) {
					throw new IllegalStateException("References not consistent");
				}
			}
			deleted = artifact == artifact.getParent().getArtifacts().remove(artifact.getName());
		}
		return deleted;
	}

	public boolean tidyOut() {
		HashSet<String> visited = new HashSet<>();
		collectFolders(visited, _root);
		return tidyOut(_root, visited);
	}

	private boolean tidyOut(Directory directory, HashSet<String> visited) {
		for (Iterator<Artifact> iterator = directory.getArtifacts().values().iterator(); iterator.hasNext();) {
			Artifact artifact = iterator.next();
			if (artifact instanceof Directory) {
				if (tidyOut((Directory) artifact, visited)) {
					logger.info("Remove: " + artifact.getURI());
					iterator.remove();
					_changes.put(artifact.getURI(), ChangeType.DELETE);
				}
			} else if (!visited.contains(artifact.getURI())) {
				logger.info("Remove: " + artifact);
				iterator.remove();
				_changes.put(artifact.getURI(), ChangeType.DELETE);
			}
		}
		return directory.getArtifacts().isEmpty();
	}

	private void collectFolders(HashSet<String> visited, Directory directory) {
		for (Artifact artifact : directory.getArtifacts().values()) {
			if (artifact instanceof ServiceArtifact) {
				collectDownwardDependencies(visited, artifact.getURI());
			} else if (artifact instanceof Directory) {
				collectFolders(visited, (Directory) artifact);
			}
		}
	}

	private void collectDownwardDependencies(HashSet<String> visited, String artifactUri) {
		if (visited.add(artifactUri)) {
			Artifact artifact = getArtifact(artifactUri);
			for (String referenced : artifact.getReferenced()) {
				collectDownwardDependencies(visited, referenced);
			}
		}
	}

	private void collectUpwardDependencies(HashSet<String> visited, String artifactUri) {
		if (visited.add(artifactUri)) {
			Artifact artifact = getArtifact(artifactUri);
			for (String referenced : artifact.getReferencedBy()) {
				collectUpwardDependencies(visited, referenced);
			}
		}
	}

	protected enum ChangeType { CREATE, UPDATE, DELETE }

	public class ChangeSet {
		private final ArrayList<Future<ServiceArtifact>> futures = new ArrayList<>();
		private final ArrayList<WorkerPoolArtifact> workerPoolArtifacts = new ArrayList<>();

		public FileSystem getFileSystem() {
			return FileSystem.this;
		}

		public ArrayList<ServiceArtifact> getServiceArtifacts() throws ValidationException {
			ArrayList<ServiceArtifact> serviceArtifacts = new ArrayList<>();
			for (Future<ServiceArtifact> future : futures) {
				try {
					serviceArtifacts.add(future.get());
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				} catch (ExecutionException e) {
					if (e.getCause() instanceof ValidationException) {
						throw (ValidationException) e.getCause();
					}
					throw new RuntimeException(e);
				} 
			}
			return serviceArtifacts;
		}

		public ArrayList<WorkerPoolArtifact> getWorkerPoolArtifacts() {
			return workerPoolArtifacts;
		}
	}

	public ChangeSet createUpdate(GlobalContext globalContext, InputStream InputStream) throws IOException, ValidationException {
		FileSystem clone = clone();
		boolean tidyOut = clone.mergeZIP(InputStream);
		ChangeSet changeSet = validateChanges(globalContext, clone);
		if (tidyOut) clone.tidyOut();
		return changeSet;
	}

	protected ChangeSet validateChanges(GlobalContext globalContext, FileSystem changedFileSystem) throws ValidationException {
		ChangeSet services = changedFileSystem.new ChangeSet();
		HashSet<String> visited = new HashSet<>();
		// find affected
		for (Entry<String, ChangeType> entry : changedFileSystem._changes.entrySet()) {
			if (entry.getValue() == ChangeType.UPDATE) {
				collectUpwardDependencies(visited, entry.getKey());
			}
		}
		// invalidate
		for (String original : visited) {
			Artifact artifact = changedFileSystem.getArtifact(original);
			artifact.setValidated(false);
		}
		// validate
		for (String original : visited) {
			Artifact artifact = changedFileSystem.getArtifact(original);
			validateServices(globalContext, artifact, services);
		}
		for (Entry<String, ChangeType> entry : changedFileSystem._changes.entrySet()) {
			if (entry.getValue() == ChangeType.CREATE) {
				validateServices(globalContext, changedFileSystem.getArtifact(entry.getKey()), services);
			}
		}
		return services;
	}
	
	private boolean mergeZIP(InputStream InputStream) throws IOException {
		boolean tidyOut = false;
		try (JarInputStream zis = new JarInputStream(InputStream)) {
			Manifest manifest = zis.getManifest();
			if (manifest != null) {
				tidyOut = Boolean.parseBoolean(manifest.getMainAttributes().getValue("tidyOut"));
				String delete = manifest.getMainAttributes().getValue("delete");
				if (delete != null) {
					StringTokenizer tokenizer = new StringTokenizer(delete, " ");
					while (tokenizer.hasMoreTokens()) {
						String uri = tokenizer.nextToken();
						Artifact artifact = getArtifact(uri);
						if (!deleteArtifact(artifact)) {
							throw new IllegalArgumentException("Could not delete " + uri);
						}
						_changes.put(artifact.getURI(), ChangeType.DELETE);
					}
				}
			}
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				if (!entry.isDirectory()) {
					int i = entry.getName().lastIndexOf('/');
					Directory dir = i < 0 ? _root : makeDirectory(entry.getName().substring(0, i));
					String name = i < 0 ? entry.getName() : entry.getName().substring(i + 1);
					Artifact old = getArtifact(entry.getName());
					Artifact artifact = createArtifact(dir, name);
					if (artifact != null) {
						ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
						ESBMessage.copyStream(zis, bos);
						artifact.setContent(bos.toByteArray());
						artifact.setModificationTime(entry.getTime());
						if (old != null) {
							if (Arrays.equals(old.getContent(), artifact.getContent())) {
								// Undo
								dir.getArtifacts().put(name, old);
							} else {
								_changes.put(artifact.getURI(), ChangeType.UPDATE);
							}
						} else {
							_changes.put(artifact.getURI(), ChangeType.CREATE);
						}
					}
				}
			}
		}
		return tidyOut;
	}

	public void writeBack(File dir) throws IOException {
		for (Entry<String, ChangeType> entry : _changes.entrySet()) {
			File file = new File(dir, entry.getKey());
			if (entry.getValue() == ChangeType.DELETE) {
				file.delete();
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
		_anchorDir = dir;
		_changes.clear();
	}

}
