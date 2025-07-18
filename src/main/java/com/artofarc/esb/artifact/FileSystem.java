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
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
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
	public final static boolean ignoreValidationExceptionsOnStartup = Boolean.parseBoolean(System.getProperty("esb0.ignoreValidationExceptionsOnStartup"));

	private final Directory _root;
	private final Map<String, ChangeType> _changes = new LinkedHashMap<>();

	public FileSystem() {
		_root = new Directory(this, null, "");
	}

	protected FileSystem(FileSystem fileSystem) {
		_root = fileSystem._root.clone(this, null);
	}

	private boolean isStartup() {
		return _changes.isEmpty();
	}

	protected abstract FileSystem copy();

	protected abstract void load() throws Exception;

	protected abstract void writeBackChanges(Map<String, ChangeType> changes) throws Exception;

	public final void writeBackChanges() throws Exception {
		writeBackChanges(_changes);
		_changes.clear();
		dehydrateArtifacts(_root);
	}

	protected InputStream createInputStream(String uri) throws Exception {
		return new ByteArrayInputStream(reloadContent(uri));
	}

	protected byte[] reloadContent(String uri) throws Exception {
		try (InputStream contentAsStream = createInputStream(uri)) {
			return IOUtils.toByteArray(contentAsStream);
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

	protected final <A extends Artifact> A loadArtifact(String uri) throws FileNotFoundException {
		return loadArtifact(_root, uri);
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
		collectArtifacts(globalContext, _root, changeSet);
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

	private static void collectArtifacts(final GlobalContext globalContext, Artifact artifact, ChangeSet changeSet) throws ValidationException {
		if (artifact instanceof Directory) {
			for (Artifact child : ((Directory) artifact).getArtifacts().values()) {
				collectArtifacts(globalContext, child, changeSet);
			}
		} else {
			changeArtifact(globalContext, artifact, changeSet);
		}
	}

	private static void changeArtifact(final GlobalContext globalContext, Artifact artifact, ChangeSet changeSet) throws ValidationException {
		if (artifact instanceof ServiceArtifact) {
			changeSet.futures.add(globalContext.getDefaultWorkerPool().getExecutorService().submit(() -> {
				artifact.validate(globalContext);
				return (ServiceArtifact) artifact;
			}));
		} else if (artifact instanceof WorkerPoolArtifact) {
			artifact.validate(globalContext);
			changeSet.getWorkerPoolArtifacts().add((WorkerPoolArtifact) artifact);
		} else if (artifact instanceof JNDIObjectFactoryArtifact) {
			artifact.validate(globalContext);
			changeSet.getJNDIObjectFactoryArtifacts().add((JNDIObjectFactoryArtifact) artifact);
		} else if (artifact instanceof JarArtifact) {
			changeSet.getJarArtifacts().add((JarArtifact) artifact);
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
		case JNDIObjectFactoryArtifact.FILE_EXTENSION:
			return new JNDIObjectFactoryArtifact(this, parent, name);
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

	final boolean tidyOut(ChangeSet changeSet) {
		HashSet<String> visited = new HashSet<>();
		collectFolders(visited, _root);
		detachOrphans(_root, visited);
		return deleteOrphans(_root, visited, changeSet);
	}

	private static void detachOrphans(Directory directory, HashSet<String> visited) {
		for (Artifact artifact : directory.getArtifacts().values()) {
			if (artifact instanceof Directory) {
				Directory child = (Directory) artifact;
				if (!XMLCatalog.isXMLCatalog(child)) {
					detachOrphans(child, visited);
				}
			} else if (!visited.contains(artifact.getURI())) {
				artifact.detachFromReferenced();
			}
		}
	}

	private boolean deleteOrphans(Directory directory, HashSet<String> visited, ChangeSet changeSet) {
		for (Iterator<Artifact> iterator = directory.getArtifacts().values().iterator(); iterator.hasNext();) {
			Artifact artifact = iterator.next();
			if (artifact instanceof Directory) {
				Directory child = (Directory) artifact;
				if (!XMLCatalog.isXMLCatalog(child) && deleteOrphans(child, visited, changeSet)) {
					logger.info("Remove: " + artifact.getURI());
					iterator.remove();
					noteChange(artifact.getURI(), ChangeType.DELETE);
				}
			} else if (!visited.contains(artifact.getURI())) {
				logger.info("Remove: " + artifact);
				iterator.remove();
				changeSet.getDeletedArtifacts().add(artifact);
				noteChange(artifact.getURI(), ChangeType.DELETE);
			}
		}
		return directory.getArtifacts().isEmpty();
	}

	private void collectFolders(HashSet<String> visited, Directory directory) {
		for (Artifact artifact : directory.getArtifacts().values()) {
			if (artifact.isValidated() && (artifact instanceof ServiceArtifact || artifact instanceof JNDIObjectFactoryArtifact)) {
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

	protected enum ChangeType { CREATE, UPDATE, DELETE, RENAME }

	public final class ChangeSet {
		private final List<Future<ServiceArtifact>> futures = new ArrayList<>();
		private final List<WorkerPoolArtifact> workerPoolArtifacts = new ArrayList<>();
		private final List<JNDIObjectFactoryArtifact> jndiObjectFactoryArtifacts = new ArrayList<>();
		private final List<JarArtifact> jarArtifacts = new ArrayList<>();
		private final List<Artifact> deletedArtifacts = new ArrayList<>();
		private List<ServiceArtifact> serviceArtifacts;

		public FileSystem getFileSystem() {
			return FileSystem.this;
		}

		public List<ServiceArtifact> getServiceArtifacts() throws ValidationException {
			if (serviceArtifacts == null) {
				serviceArtifacts = new ArrayList<>(futures.size());
				HashSet<ValidationException> validationExceptions = new HashSet<>();
				for (Future<ServiceArtifact> future : futures) {
					try {
						serviceArtifacts.add(future.get());
					} catch (InterruptedException e) {
						throw new RuntimeException(e);
					} catch (ExecutionException e) {
						if (e.getCause() instanceof ValidationException) {
							validationExceptions.add((ValidationException) e.getCause());
						} else {
							throw ReflectionUtils.convert(e.getCause(), RuntimeException.class);
						}
					}
				}
				if (validationExceptions.size() > 0) {
					Iterator<ValidationException> iterator = validationExceptions.iterator();
					ValidationException exception = iterator.next();
					while (iterator.hasNext())
						exception.addSuppressed(iterator.next());
					if (ignoreValidationExceptionsOnStartup && isStartup()) {
						logger.warn("ValidationExceptions on startup", exception);
					} else {
						throw exception;
					}
				}
				dehydrateArtifacts(_root);
			}
			return serviceArtifacts;
		}

		public List<WorkerPoolArtifact> getWorkerPoolArtifacts() {
			return workerPoolArtifacts;
		}

		public List<JNDIObjectFactoryArtifact> getJNDIObjectFactoryArtifacts() {
			return jndiObjectFactoryArtifacts;
		}

		public List<JarArtifact> getJarArtifacts() {
			return jarArtifacts;
		}

		public List<Artifact> getDeletedArtifacts() {
			return deletedArtifacts;
		}
	}

	final void noteChange(String uri, ChangeType type) {
		_changes.merge(uri, type, (oldValue, newValue) -> oldValue == ChangeType.CREATE ? oldValue : newValue);
	}

	public final ChangeSet createChangeSet(GlobalContext globalContext, InputStream inputStream) throws IOException, ValidationException {
		FileSystem copy = copy();
		boolean tidyOut = copy.mergeZIP(inputStream);
		ChangeSet changeSet = collectChanges(globalContext, copy);
		if (tidyOut) copy.tidyOut(changeSet);
		return changeSet;
	}

	public final ChangeSet createChangeSet(GlobalContext globalContext, String uri, byte[] content, Charset charset) throws ValidationException {
		FileSystem copy = copy();
		CRC32 crc = new CRC32();
		crc.update(content);
		Artifact artifact = copy.getArtifact(copy.getRoot(), uri);
		if (artifact != null) {
			if (artifact.isDifferent(content, crc.getValue())) {
				artifact.setContent(content, charset);
				artifact.setModificationTime(System.currentTimeMillis());
				artifact.setCrc(crc.getValue());
				copy.noteChange(uri, ChangeType.UPDATE);
			}
		} else {
			artifact = copy.createArtifact(uri.substring(1));
			artifact.setContent(content, charset);
			artifact.setModificationTime(System.currentTimeMillis());
			artifact.setCrc(crc.getValue());
			copy.noteChange(uri, ChangeType.CREATE);
		}
		return collectChanges(globalContext, copy);
	}

	public final ChangeSet createChangeSet(GlobalContext globalContext, String uriToDelete) throws FileNotFoundException, ValidationException {
		FileSystem copy = copy();
		Artifact artifact = copy.loadArtifact(uriToDelete);
		if (!artifact.delete()) {
			throw new ValidationException(artifact, "Could not delete " + artifact.getURI());
		}
		copy.noteChange(uriToDelete, ChangeType.DELETE);
		ChangeSet changeSet = copy.new ChangeSet();
		changeSet.getDeletedArtifacts().add(artifact);
		copy.tidyOut(changeSet);
		return changeSet;
	}

	private ChangeSet collectChanges(GlobalContext globalContext, FileSystem changedFileSystem) throws ValidationException {
		ChangeSet changeSet = changedFileSystem.new ChangeSet();
		HashSet<String> visited = new HashSet<>();
		// find affected
		for (Map.Entry<String, ChangeType> entry : changedFileSystem._changes.entrySet()) {
			if (entry.getValue() == ChangeType.UPDATE) {
				collectUpwardDependencies(visited, entry.getKey());
			}
		}
		// invalidate
		ArrayDeque<Artifact> orphans = new ArrayDeque<>();
		for (String original : visited) {
			Artifact artifact = changedFileSystem.getArtifact(original);
			artifact.invalidate(orphans);
		}
		// validate
		for (String original : visited) {
			Artifact artifact = changedFileSystem.getArtifact(original);
			changeArtifact(globalContext, artifact, changeSet);
		}
		for (Map.Entry<String, ChangeType> entry : changedFileSystem._changes.entrySet()) {
			Artifact artifact = changedFileSystem.getArtifact(entry.getKey());
			if (entry.getValue() == ChangeType.CREATE) {
				changeArtifact(globalContext, artifact, changeSet);
			} else if (entry.getValue() == ChangeType.DELETE) {
				if (!artifact.delete()) {
					throw new ValidationException(artifact, "Could not delete " + artifact.getURI());
				}
				changeSet.getDeletedArtifacts().add(artifact);
			}
		}
		changeSet.getServiceArtifacts();
		Artifact orphan;
		while ((orphan = orphans.poll()) != null) {
			if (orphan.getReferencedBy().isEmpty()) {
				orphan.invalidate(orphans);
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
						noteChange(loadArtifact(uri).getURI(), ChangeType.DELETE);
					}
				}
			}
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				if (!entry.isDirectory()) {
					Artifact artifact = getArtifact(entry.getName());
					if (artifact != null) {
						byte[] content = IOUtils.toByteArray(zis);
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
							artifact.setContent(IOUtils.toByteArray(zis));
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

	public final void dump(OutputStream outputStream) throws IOException {
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
				artifact.getContentAsStream().transferTo(zos);
			}
		}
	}

}
