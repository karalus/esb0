/*
 * Copyright 2021 Andre Karalus
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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.util.ByteArrayInputStream;
import com.artofarc.util.IOUtils;
import com.artofarc.util.ReflectionUtils;

public abstract class FileSystem {

	protected final static Logger logger = LoggerFactory.getLogger(FileSystem.class);

	public final static String environment = System.getProperty("esb0.environment", "default");

	protected final Directory _root;
	protected final Map<String, ChangeType> _changes = new LinkedHashMap<>();

	public FileSystem() {
		_root = new Directory(this, null, "");
	}

	protected FileSystem(FileSystem fileSystem) {
		_root = fileSystem._root.clone(this, null);
	}

	public abstract FileSystem copy();

	public abstract void load() throws Exception;

	public abstract void writeBackChanges() throws Exception;

	protected InputStream createInputStream(String uri) throws Exception {
		return new ByteArrayInputStream(reloadContent(uri));
	}

	protected byte[] reloadContent(String uri) throws Exception {
		try (InputStream contentAsStream = createInputStream(uri)) {
			return IOUtils.copy(contentAsStream);
		}
	}

	public final Directory getRoot() {
		return _root;
	}

	public final <A extends Artifact> A getArtifact(String uri) {
		return getArtifact(_root, uri);
	}

	@SuppressWarnings("unchecked")
	protected final <A extends Artifact> A getArtifact(Directory current, String uri) {
		if (current == null) current = _root;
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

	protected final <A extends Artifact> A loadArtifact(Directory current, String uri) throws FileNotFoundException {
		A artifact = getArtifact(current, uri);
		if (artifact == null) {
			throw new FileNotFoundException(uri);
		}
		return artifact;
	}

	public final ChangeSet init(GlobalContext globalContext) throws Exception {
		load();
		ChangeSet changeSet = new ChangeSet();
		validateServices(globalContext, _root, changeSet);
		return changeSet;
	}

	public final void dehydrateArtifacts(Directory base) {
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
		} else {
			validateService(globalContext, artifact, changeSet);
		}
	}

	private static void validateService(final GlobalContext globalContext, Artifact artifact, ChangeSet changeSet) throws ValidationException {
		if (artifact instanceof ServiceArtifact) {
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
		} else if (artifact instanceof DataSourceArtifact) {
			artifact.validate(globalContext);
			changeSet.getDataSourceArtifacts().add((DataSourceArtifact) artifact);
		}
	}

	protected final Artifact createArtifact(String uri) {
		int i = uri.lastIndexOf('/');
		if (i < 0) {
			return createArtifact(_root, uri);
		} else {
			return createArtifact(makeDirectory(uri.substring(0, i)), uri.substring(i + 1));
		}
	}

	protected final Artifact createArtifact(Directory parent, String name) {
		switch (IOUtils.getExt(name)) {
		case ServiceArtifact.FILE_EXTENSION:
			return new ServiceArtifact(this, parent, name);
		case "xml":
			return new XMLProcessingArtifact(this, parent, name);
		case "xsd":
			return new XSDArtifact(this, parent, name);
		case "wsdl":
			return new WSDLArtifact(this, parent, name);
		case "xq":
		case "xqy":
		case "xqm":
			return new XQueryArtifact(this, parent, name);
		case "xsl":
		case "xslt":
			return new XSLTArtifact(this, parent, name);
		case WorkerPoolArtifact.FILE_EXTENSION:
			return new WorkerPoolArtifact(this, parent, name);
		case DataSourceArtifact.FILE_EXTENSION:
			return new DataSourceArtifact(this, parent, name);
		case ClassLoaderArtifact.FILE_EXTENSION:
			return new ClassLoaderArtifact(this, parent, name);
		case "jar":
			return new JarArtifact(this, parent, name);
		case "json":
			return new JSONArtifact(this, parent, name);
		default:
			logger.debug("Cannot be imported: " + name);
			return null;
		}
	}

	protected final Directory makeDirectory(String path) {
		Directory result = _root;
		String[] split = path.split("/");
		for (String name : split) {
			Artifact artifact = result.getArtifacts().get(name);
			if (artifact == null) {
				result = new Directory(this, result, name);
				noteChange(result.getURI(), ChangeType.CREATE);
			} else if (artifact instanceof Directory) {
				result = (Directory) artifact;
			} else {
				throw new IllegalStateException("Is not a directory: " + artifact);
			}
		}
		return result;
	}

	public final boolean tidyOut() {
		HashSet<String> visited = new HashSet<>();
		collectFolders(visited, _root);
		detachOrphans(_root, visited);
		return deleteOrphans(_root, visited);
	}

	private static void detachOrphans(Directory directory, HashSet<String> visited) {
		for (Artifact artifact : directory.getArtifacts().values()) {
			if (artifact instanceof Directory) {
				Directory child = (Directory) artifact;
				if (!XMLCatalog.isXMLCatalog(child)) {
					detachOrphans(child, visited);
				}
			} else if (!(artifact instanceof DataSourceArtifact || visited.contains(artifact.getURI()))) {
				artifact.detachFromReferenced();
			}
		}
	}

	private boolean deleteOrphans(Directory directory, HashSet<String> visited) {
		for (Iterator<Artifact> iterator = directory.getArtifacts().values().iterator(); iterator.hasNext();) {
			Artifact artifact = iterator.next();
			if (artifact instanceof Directory) {
				Directory child = (Directory) artifact;
				if (!XMLCatalog.isXMLCatalog(child) && deleteOrphans(child, visited)) {
					logger.info("Remove: " + artifact.getURI());
					iterator.remove();
					noteChange(artifact.getURI(), ChangeType.DELETE);
				}
			} else if (!(artifact instanceof DataSourceArtifact || visited.contains(artifact.getURI()))) {
				logger.info("Remove: " + artifact);
				iterator.remove();
				noteChange(artifact.getURI(), ChangeType.DELETE);
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

	public final class ChangeSet {
		private final List<Future<ServiceArtifact>> futures = new ArrayList<>();
		private final List<WorkerPoolArtifact> workerPoolArtifacts = new ArrayList<>();
		private final List<DataSourceArtifact> dataSourceArtifacts = new ArrayList<>();
		private final List<Artifact> deletedArtifacts = new ArrayList<>();

		public FileSystem getFileSystem() {
			return FileSystem.this;
		}

		public List<ServiceArtifact> getServiceArtifacts() throws ValidationException {
			List<ServiceArtifact> serviceArtifacts = new ArrayList<>();
			ValidationException exception = null;
			for (Future<ServiceArtifact> future : futures) {
				try {
					serviceArtifacts.add(future.get());
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				} catch (ExecutionException e) {
					if (e.getCause() instanceof ValidationException) {
						if (exception == null) {
							exception = (ValidationException) e.getCause();
						} else {
							exception.addSuppressed(e.getCause());
						}
					} else {
						throw ReflectionUtils.convert(e.getCause(), RuntimeException.class);
					}
				} 
			}
			if (exception != null) {
				throw exception;
			}
			dehydrateArtifacts(_root);
			return serviceArtifacts;
		}

		public List<WorkerPoolArtifact> getWorkerPoolArtifacts() {
			return workerPoolArtifacts;
		}

		public List<DataSourceArtifact> getDataSourceArtifacts() {
			return dataSourceArtifacts;
		}

		public List<Artifact> getDeletedArtifacts() {
			return deletedArtifacts;
		}
	}

	final void noteChange(String uri, ChangeType type) {
		_changes.putIfAbsent(uri, type);
	}

	public final ChangeSet createChangeSet(GlobalContext globalContext, InputStream inputStream) throws IOException, ValidationException {
		FileSystem copy = copy();
		boolean tidyOut = copy.mergeZIP(inputStream);
		ChangeSet changeSet = validateChanges(globalContext, copy);
		if (tidyOut) copy.tidyOut();
		return changeSet;
	}

	public final ChangeSet createChangeSet(GlobalContext globalContext, String uri, byte[] content) throws Exception {
		FileSystem copy = copy();
		CRC32 crc = new CRC32();
		crc.update(content);
		Artifact artifact = copy.getArtifact(copy.getRoot(), uri);
		if (artifact != null) {
			if (artifact.isDifferent(content, crc.getValue())) {
				artifact.setContent(content);
				artifact.setModificationTime(System.currentTimeMillis());
				artifact.setCrc(crc.getValue());
				copy.noteChange(uri, ChangeType.UPDATE);
			}
		} else {
			artifact = copy.createArtifact(uri.substring(1));
			artifact.setContent(content);
			artifact.setModificationTime(System.currentTimeMillis());
			artifact.setCrc(crc.getValue());
			copy.noteChange(uri, ChangeType.CREATE);
		}
		return validateChanges(globalContext, copy);
	}

	public final ChangeSet createChangeSet(GlobalContext globalContext, String uriToDelete) throws FileNotFoundException, ValidationException {
		FileSystem copy = copy();
		Artifact artifact = copy.loadArtifact(copy.getRoot(), uriToDelete);
		if (!artifact.delete()) {
			throw new ValidationException(artifact, "Could not delete " + artifact.getURI());
		}
		copy.noteChange(uriToDelete, ChangeType.DELETE);
		ChangeSet changeSet = copy.new ChangeSet();
		changeSet.getDeletedArtifacts().add(artifact);
		copy.tidyOut();
		return changeSet;
	}

	private ChangeSet validateChanges(GlobalContext globalContext, FileSystem changedFileSystem) throws ValidationException {
		ChangeSet changeSet = changedFileSystem.new ChangeSet();
		HashSet<String> visited = new HashSet<>();
		// find affected
		for (Map.Entry<String, ChangeType> entry : changedFileSystem._changes.entrySet()) {
			if (entry.getValue() == ChangeType.UPDATE) {
				collectUpwardDependencies(visited, entry.getKey());
			}
		}
		// invalidate
		for (String original : visited) {
			Artifact artifact = changedFileSystem.getArtifact(original);
			artifact.invalidate();
		}
		// validate
		for (String original : visited) {
			Artifact artifact = changedFileSystem.getArtifact(original);
			validateService(globalContext, artifact, changeSet);
		}
		for (Map.Entry<String, ChangeType> entry : changedFileSystem._changes.entrySet()) {
			Artifact artifact = changedFileSystem.getArtifact(entry.getKey());
			if (entry.getValue() == ChangeType.CREATE) {
				validateService(globalContext, artifact, changeSet);
			} else if (entry.getValue() == ChangeType.DELETE) {
				if (!artifact.delete()) {
					throw new ValidationException(artifact, "Could not delete " + artifact.getURI());
				}
				changeSet.getDeletedArtifacts().add(artifact);
			}
		}
		return changeSet;
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
						noteChange(loadArtifact(_root, uri).getURI(), ChangeType.DELETE);
					}
				}
			}
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				if (!entry.isDirectory()) {
					Artifact artifact = getArtifact(entry.getName());
					if (artifact != null) {
						byte[] content = IOUtils.copy(zis);
						if (artifact.isDifferent(content, entry.getCrc())) {
							artifact.setContent(content);
							artifact.setModificationTime(entry.getTime());
							artifact.setCrc(entry.getCrc());
							noteChange(artifact.getURI(), ChangeType.UPDATE);
						} else if (artifact.getContent() == null) {
							// Keep content (until dehydrateArtifacts happens), it might be needed by Resolvers during validation
							artifact.setContent(content);
						}
					} else {
						artifact = createArtifact(entry.getName());
						if (artifact != null) {
							artifact.setContent(IOUtils.copy(zis));
							artifact.setModificationTime(entry.getTime());
							artifact.setCrc(entry.getCrc());
							noteChange(artifact.getURI(), ChangeType.CREATE);
						}
					}
				}
			}
		}
		return tidyOut;
	}

	public void dump(OutputStream outputStream) throws IOException {
		try (ZipOutputStream zos = new ZipOutputStream(outputStream)) {
			dumpDirectory(zos, _root);
		}
	}

	private static void dumpDirectory(ZipOutputStream zos, Directory directory) throws IOException {
		for (Artifact artifact : directory.getArtifacts().values()) {
			if (artifact instanceof Directory) {
				dumpDirectory(zos, (Directory) artifact);
			} else if (artifact.getModificationTime() > 0L) {
				ZipEntry zipEntry = new ZipEntry(artifact.getURI().substring(1));
				zipEntry.setTime(artifact.getModificationTime());
				zos.putNextEntry(zipEntry);
				IOUtils.copy(artifact.getContentAsStream(), zos);
			}
		}
	}

}
