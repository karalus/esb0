/*
 * Copyright 2022 Andre Karalus
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
import java.lang.ref.WeakReference;
import java.util.ArrayList;

import javax.xml.transform.ErrorListener;
import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;
import javax.xml.transform.stream.StreamSource;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.util.IOUtils;
import com.artofarc.util.XMLProcessorFactory;

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
		// checks well-formedness
		XMLProcessorFactory.getSAXParserFactory().newSAXParser().parse(getContentAsStream(), new org.xml.sax.helpers.DefaultHandler());
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
		if (!IOUtils.getExt(getName()).equals("xml")) {
			super.clearContent();
		}
	}

	abstract public static class AbstractURIResolver implements URIResolver {

		private static final String PROTOCOL_FILE = "file:";

		protected abstract Artifact getBaseArtifact();

		Artifact resolveArtifact(String href, String base) throws FileNotFoundException {
			if (base != null && !base.isEmpty()) {
				if (base.startsWith(PROTOCOL_FILE)) {
					base = base.substring(PROTOCOL_FILE.length());
				}
				if (href.startsWith("/.")) {
					return getBaseArtifact().loadArtifact(base + href);
				} else {
					return getBaseArtifact().loadArtifact(base).loadArtifact(href);
				}
			} else {
				if (href.startsWith(PROTOCOL_FILE)) {
					href = href.substring(PROTOCOL_FILE.length());
				}
				return getBaseArtifact().loadArtifact(href);
			}
		}

		@Override
		public StreamSource resolve(String href, String base) throws TransformerException {
			try {
				Artifact artifact = resolveArtifact(href, base);
				return new StreamSource(artifact.getContentAsStream(), artifact.getURI());
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

	static final class ValidationErrorListener implements ErrorListener {

		private final String uri;
		private final ArrayList<TransformerException> exceptions = new ArrayList<>();

		ValidationErrorListener(String uri) {
			this.uri = uri;
		}

		@Override
		public void warning(TransformerException exception) {
			logger.warn(uri, exception);
		}

		@Override
		public void fatalError(TransformerException exception) {
			logger.error(uri, exception);
			exceptions.add(exception);
		}

		@Override
		public void error(TransformerException exception) {
			logger.error(uri, exception);
			exceptions.add(exception);
		}

		public Exception build(Exception e) {
			switch (exceptions.size()) {
			case 0:
				return e;
			case 1:
				return exceptions.get(0);
			default:
				for (TransformerException exception : exceptions) {
					e.addSuppressed(exception);
				}
				return e;
			}
		}
	}

}
