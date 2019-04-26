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
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.util.ReflectionUtils;

public abstract class Artifact {

	protected final static Logger logger = LoggerFactory.getLogger(Artifact.class);

	private final FileSystem _fileSystem;
	private final Directory _parent;
	private final String _name;
	private final LinkedHashSet<String> _referenced = new LinkedHashSet<>();
	private final LinkedHashSet<String> _referencedBy = new LinkedHashSet<>();

	protected byte[] _content;
	protected int _length;
	protected long _crc;
	private long _modificationTime;
	private boolean _validated;

	protected Artifact(FileSystem fileSystem, Directory parent, String name) {
		_fileSystem = fileSystem;
		_parent = parent;
		_name = name;
		if (parent != null) {
			parent.getArtifacts().put(name, this);
		}
	}

	public final boolean isEqual(Artifact other) {
		if (_content != null) {
			return Arrays.equals(_content, other._content);
		}
		// not 100% certain, but very likely
		return _length == other._length && _crc == other._crc;
	}

	public final Directory getParent() {
		return _parent;
	}

	public final String getName() {
		return _name;
	}

	public final String getURI() {
		if (_parent != null) {
			return _parent.getURI() + '/' + _name;
		} else {
			return _name;
		}
	}

	public static String stripExt(String name) {
		int i = name.lastIndexOf('.');
		return i > 0 ? name.substring(0, i) : name;
	}

	public static String getExt(String name) {
		int i = name.lastIndexOf('.');
		return i < 0 ? "" : name.substring(i + 1);
	}

	public final Collection<String> getReferenced() {
		return _referenced;
	}

	public final Collection<String> getReferencedBy() {
		return _referencedBy;
	}

	public final boolean isValidated() {
		return _validated;
	}

	public final void setValidated(boolean validated) {
		if (!(_validated = validated)) {
			for (Iterator<String> iterator = _referenced.iterator(); iterator.hasNext();) {
				Artifact artifact = getArtifact(iterator.next());
				artifact.getReferencedBy().remove(getURI());
				iterator.remove();
			}
		}
	}

	public final byte[] getContent() {
		return _content;
	}

	public final void setContent(byte[] content) {
		_content = content;
		_length = content.length;
	}

	/**
	 * Override when content should be kept in memory.
	 */
	protected void clearContent() {
		_content = null;
	}

	public final long getModificationTime() {
		return _modificationTime;
	}

	public final void setModificationTime(long modificationTime) {
		_modificationTime = modificationTime;
	}

	public final void setCrc(long crc) {
		_crc = crc;
	}

	public final InputStream getContentAsStream() {
		return _content != null ? new ByteArrayInputStream(_content) : _fileSystem.createInputStream(getURI());
	}

	protected final byte[] getContentAsBytes() throws Exception {
		if (_content == null) {
			_content = _fileSystem.reloadContent(getURI());
		}
		return _content;
	}

	public String getContentType() {
		return "text/plain;charset=utf-8";		
	}

	public final <A extends Artifact> A getArtifact(String uri) {
		return _fileSystem.getArtifact(getParent(), uri);
	}

	protected final <A extends Artifact> A loadArtifact(String uri) throws FileNotFoundException {
		return _fileSystem.loadArtifact(getParent(), uri);
	}

	protected final void addReference(Artifact artifact) {
		if (artifact != this) {
			getReferenced().add(artifact.getURI());
			artifact.getReferencedBy().add(getURI());
			logger.debug(getURI() + "->" + artifact);
		}
	}

	protected abstract void validateInternal(GlobalContext globalContext) throws Exception;

	protected void postValidateInternal(GlobalContext globalContext) throws ValidationException {
	}

	public final void validate(GlobalContext globalContext) throws ValidationException {
		synchronized (this) {
			if (isValidated()) {
				return;
			} else {
				try {
					validateInternal(globalContext);
				} catch (Exception e) {
					throw ReflectionUtils.convert(e, ValidationException.class, this);
				}
				setValidated(true);
				logger.info("Validated: " + getURI());
			}
		}
		postValidateInternal(globalContext);
	}

	@Override
	public String toString() {
		return getURI();
	}

	protected final <A extends Artifact> A initClone(A clone) {
		if (isValidated()) {
			clone.setValidated(true);
			clone.getReferenced().addAll(getReferenced());
			clone.getReferencedBy().addAll(getReferencedBy());
		}
		clone.setModificationTime(getModificationTime());
		clone._crc = _crc;
		clone._content = _content;
		clone._length = _length;
		return clone;
	}

	protected abstract Artifact clone(FileSystem fileSystem, Directory parent);

	protected final ArtifactURIResolver getURIResolver() {
		return new ArtifactURIResolver() {
			@Override
			public Artifact resolveArtifact(String path) {
				Artifact artifact = getArtifact(path);
				if (artifact != null) {
					addReference(artifact);
				}
				return artifact;
			}
		};
	}

}
