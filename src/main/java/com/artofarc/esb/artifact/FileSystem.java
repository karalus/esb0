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
import com.artofarc.util.ReflectionUtils;
import com.artofarc.util.StreamUtils;

import java.io.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FileSystem {

	protected final static Logger logger = LoggerFactory.getLogger(FileSystem.class);

	private final File _anchorDir;
	private final Directory _root;
	private final Map<String, ChangeType> _changes = new LinkedHashMap<>();

	public FileSystem(FileSystem fileSystem) {
		_anchorDir = fileSystem._anchorDir;
		_root = fileSystem._root.clone(this, null);
	}

	public FileSystem(File anchorDir) {
		_anchorDir = anchorDir;
		_root = new Directory(this, null, "");
	}

	public Directory getRoot() {
		return _root;
	}

	protected InputStream reloadInputStream(String uri) {
		try {
			return new FileInputStream(new File(_anchorDir, uri));
		} catch (FileNotFoundException e) {
			throw new RuntimeException("FileSystem corrupted", e);
		}
	}

	public <A extends Artifact> A getArtifact(String uri) {
		return getArtifact(_root, uri);
	}

	@SuppressWarnings("unchecked")
	protected <A extends Artifact> A getArtifact(Directory current, String uri) {
		if (uri == null) return (A) current;
		int i = 0, j;
		while ((j = uri.indexOf('/', i)) >= 0) {
			String dir = uri.substring(i, j);
			switch (dir) {
			case "":
				if (current.getParent() != null) {
					current = _root;
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
		String name = uri.substring(i);
		return (A) (name.isEmpty() ? current : current.getArtifacts().get(name));
	}

	public ChangeSet parseDirectory(GlobalContext globalContext) throws IOException, ValidationException {
		readDir(_root, _anchorDir, new CRC32());
		ChangeSet services = new ChangeSet();
		validateServices(globalContext, _root, services);
		return services;
	}

	private void dehydrateArtifacts(Directory base) {
		for (Artifact artifact : base.getArtifacts().values()) {
			if (artifact instanceof Directory) {
				dehydrateArtifacts((Directory) artifact);
			} else {
				if (!_changes.containsKey(artifact.getURI())) {
					artifact.clearContent();
				}
			}
		}
	}

	private static void validateServices(final GlobalContext globalContext, Artifact artifact, ChangeSet changeSet) throws ValidationException {
		if (artifact instanceof Directory) {
			for (Artifact child : ((Directory) artifact).getArtifacts().values()) {
				validateServices(globalContext, child, changeSet);
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

	private void readDir(Directory base, File dir, CRC32 crc) throws IOException {
		for (File file : dir.listFiles()) {
			String name = file.getName();
			if (file.isDirectory()) {
				readDir(new Directory(this, base, name), file, crc);
			} else {
				Artifact artifact = createArtifact(base, name);
				if (artifact != null) {
					artifact.setContent(StreamUtils.readFile(file));
					artifact.setModificationTime(file.lastModified());
					crc.update(artifact.getContent());
					artifact.setCrc(crc.getValue());
					crc.reset();
				}
			}
		}
	}

	private Artifact createArtifact(Directory parent, String name) {
		// Mac OSX
		if (name.startsWith("._"))
			return null;
		switch (Artifact.getExt(name)) {
		case ServiceArtifact.FILE_EXTENSION:
			return new ServiceArtifact(this, parent, name);
		case "xml":
			return new XMLArtifact(this, parent, name);
		case "xsd":
			return new XSDArtifact(this, parent, name);
		case "wsdl":
			return new WSDLArtifact(this, parent, name);
		case "xq":
		case "xqy":
		case XQueryArtifact.FILE_EXTENSION_XQUERY_MODULE:
			return new XQueryArtifact(this, parent, name);
		case "xsl":
		case "xslt":
			return new XSLTArtifact(this, parent, name);
		case WorkerPoolArtifact.FILE_EXTENSION:
			return new WorkerPoolArtifact(this, parent, name);
		case ClassLoaderArtifact.FILE_EXTENSION:
			return new ClassLoaderArtifact(this, parent, name);
		case "jar":
			return new JarArtifact(this, parent, name);
		default:
			logger.debug("Cannot be imported: " + name);
			return null;
		}
	}

	protected Directory makeDirectory(String path) {
		Directory result = _root;
		String[] split = path.split("/");
		for (String name : split) {
			Artifact artifact = result.getArtifacts().get(name);
			if (artifact == null) {
				result = new Directory(this, result, name);
				_changes.put(result.getURI(), ChangeType.CREATE);
			} else if (artifact instanceof Directory) {
				result = (Directory) artifact;
			} else {
				throw new IllegalStateException("Is not a directory: " + artifact);
			}
		}
		return result;
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
				Directory child = (Directory) artifact;
				if (!XMLCatalog.isXMLCatalog(child) && tidyOut(child, visited)) {
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
					throw ReflectionUtils.convert(e.getCause(), ValidationException.class);
				} 
			}
			dehydrateArtifacts(_root);
			return serviceArtifacts;
		}

		public ArrayList<WorkerPoolArtifact> getWorkerPoolArtifacts() {
			return workerPoolArtifacts;
		}
	}

	public ChangeSet createUpdate(GlobalContext globalContext, InputStream inputStream) throws IOException, ValidationException {
		FileSystem clone = new FileSystem(this);
		boolean tidyOut = clone.mergeZIP(inputStream);
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
	
	private boolean mergeZIP(InputStream inputStream) throws IOException {
		boolean tidyOut = false;
		try (JarInputStream zis = new JarInputStream(inputStream)) {
			Manifest manifest = zis.getManifest();
			if (manifest != null) {
				tidyOut = Boolean.parseBoolean(manifest.getMainAttributes().getValue("tidyOut"));
				String delete = manifest.getMainAttributes().getValue("delete");
				if (delete != null) {
					StringTokenizer tokenizer = new StringTokenizer(delete, ", ");
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
						artifact.setContent(StreamUtils.copy(zis));
						artifact.setModificationTime(entry.getTime());
						artifact.setCrc(entry.getCrc());
						if (old != null) {
							if (old.isEqual(artifact)) {
								// Undo
								dir.getArtifacts().put(name, old);
								if (old.getContent() == null) {
									// Keep content (until dehydrateArtifacts happens), it might be needed by Resolvers during validation
									old.setContent(artifact.getContent());
								}
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

	public void writeBackChanges() throws IOException {
		for (Entry<String, ChangeType> entry : _changes.entrySet()) {
			File file = new File(_anchorDir, entry.getKey());
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
		_changes.clear();
		dehydrateArtifacts(_root);
	}

}
