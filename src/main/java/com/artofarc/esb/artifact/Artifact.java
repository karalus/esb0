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
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.http.HttpConstants;
import com.artofarc.util.ByteArrayInputStream;
import com.artofarc.util.ReflectionUtils;

public abstract class Artifact {

	protected final static Logger logger = LoggerFactory.getLogger(Artifact.class);

	private final FileSystem _fileSystem;
	private final Directory _parent;
	private final String _name;
	private final HashSet<String> _referenced = new HashSet<>();
	private final HashSet<String> _referencedBy = new HashSet<>();

	protected byte[] _content;
	protected int _length;
	private long _crc;
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

	protected final void noteChange() {
		synchronized (_fileSystem) {
			_fileSystem.noteChange(getURI(), FileSystem.ChangeType.UPDATE);
		}
	}

	protected final boolean isDifferent(byte[] content, long crc) {
		if (_content != null) {
			return !Arrays.equals(_content, content);
		}
		// not 100% certain, but very likely
		return _length != content.length || _crc != crc;
	}

	public final Directory getParent() {
		return _parent;
	}

	public final String getName() {
		return _name;
	}

	public final String getURI() {
		if (_parent != null) {
			return (_parent.getURI() + '/' + _name).intern();
		} else {
			return _name;
		}
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

	protected final void setValidated() {
		_validated = true;
	}

	public final byte[] getContent() {
		return _content;
	}

	public final void setContent(byte[] content) {
		_content = content;
		_length = content.length;
	}

	/**
	 * Override when content should be kept in memory or intermediate objects should be disposed.
	 */
	protected void clearContent() {
		// don't clear artificial artifacts like "Admin.xservice"
		if (_modificationTime > 0L) {
			_content = null;
		}
	}

	public final long getModificationTime() {
		return _modificationTime;
	}

	public final void setModificationTime(long modificationTime) {
		_modificationTime = modificationTime;
	}

	protected final void setCrc(long crc) {
		_crc = crc;
	}

	public final InputStream getContentAsStream() {
		if (_content != null) {
			return new ByteArrayInputStream(_content);
		} else {
			try {
				return _fileSystem.createInputStream(getURI());
			} catch (Exception e) {
				throw ReflectionUtils.convert(e, RuntimeException.class);
			}
		}
	}

	protected final byte[] getContentAsBytes() throws Exception {
		if (_content == null) {
			_content = _fileSystem.reloadContent(getURI());
		}
		return _content;
	}

	public String getContentType() {
		return HttpConstants.HTTP_HEADER_CONTENT_TYPE_TEXT;
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
			synchronized (artifact) {
				artifact.getReferencedBy().add(getURI());
			}
			logger.debug(getURI() + "->" + artifact);
		}
	}

	protected final void detachFromReferenced() {
		for (String referenced : getReferenced()) {
			Artifact referencedArtifact = getArtifact(referenced);
			if (!referencedArtifact.getReferencedBy().remove(getURI())) {
				throw new IllegalStateException("References not consistent for " + getURI());
			}
		}
	}

	protected final boolean delete() {
		boolean deleted = false;
		if (getReferencedBy().isEmpty()) {
			detachFromReferenced();
			deleted = this == getParent().getArtifacts().remove(getName());
		}
		return deleted;
	}

	protected abstract void validateInternal(GlobalContext globalContext) throws Exception;

	protected void postValidateInternal(GlobalContext globalContext) throws ValidationException {
	}

	protected void invalidate() {
		_validated = false;
		for (Iterator<String> iterator = _referenced.iterator(); iterator.hasNext();) {
			Artifact artifact = getArtifact(iterator.next());
			Collection<String> referencedBy = artifact.getReferencedBy();
			referencedBy.remove(getURI());
			if (referencedBy.isEmpty()) {
				artifact.invalidate();
			}
			iterator.remove();
		}
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
				setValidated();
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
			clone.setValidated();
			clone.getReferenced().addAll(getReferenced());
			clone.getReferencedBy().addAll(getReferencedBy());
		}
		clone.setModificationTime(getModificationTime());
		clone.setCrc(_crc);
		clone._content = _content;
		clone._length = _length;
		return clone;
	}

	protected abstract Artifact clone(FileSystem fileSystem, Directory parent);

}
