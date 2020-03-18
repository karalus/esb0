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
import java.lang.ref.WeakReference;

import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;
import javax.xml.transform.stream.StreamSource;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.util.IOUtils;

public class XMLProcessingArtifact extends Artifact {

	public XMLProcessingArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	@Override
	protected XMLProcessingArtifact clone(FileSystem fileSystem, Directory parent) {
		return initClone(new XMLProcessingArtifact(fileSystem, parent, getName()));
	}

	@Override
	protected void validateInternal(GlobalContext globalContext) throws Exception {
	}

	@Override
	protected void clearContent() {
		for (String referencedBy : getReferencedBy()) {
			Artifact referencedArtifact = getArtifact(referencedBy);
			if (referencedArtifact instanceof XMLProcessingArtifact) {
				// is needed for compilation of XQuery or XSLT
				return;
			}
		}
		// keep XML docs in cache
		if (!IOUtils.getExt(getName()).equals(IOUtils.FILE_EXTENSION_XML_DOC)) {
			super.clearContent();
		}
	}

	abstract public static class AbstractURIResolver implements URIResolver {

		protected abstract Artifact getBaseArtifact();

		Artifact resolveArtifact(String href, String base) throws FileNotFoundException {
			if (base != null && !base.isEmpty()) {
				if (href.startsWith("/.")) {
					return getBaseArtifact().loadArtifact(base + href);
				} else {
					return getBaseArtifact().loadArtifact(base).loadArtifact(href);
				}
			} else {
				return getBaseArtifact().loadArtifact(href);
			}
		}

		@Override
		public StreamSource resolve(String href, String base) throws TransformerException {
			try {
				Artifact artifact = resolveArtifact(href, base);
				StreamSource source = new StreamSource(artifact.getContentAsStream());
				source.setSystemId(artifact.getURI());
				return source;
			} catch (FileNotFoundException e) {
				throw new TransformerException(e);
			}
		}
	}

	static final class ArtifactURIResolver extends AbstractURIResolver {

		private final WeakReference<Artifact> _artifact;

		ArtifactURIResolver(Artifact base) {
			_artifact = new WeakReference<>(base);
		}

		@Override
		protected Artifact getBaseArtifact() {
			Artifact baseArtifact = _artifact.get();
			if (baseArtifact == null) {
				throw new IllegalStateException("Reference has already been cleared");
			}
			return baseArtifact;
		}

		@Override
		Artifact resolveArtifact(String href, String base) throws FileNotFoundException {
			Artifact artifact = super.resolveArtifact(href, base);
			_artifact.get().addReference(artifact);
			return artifact;
		}
	}

}
