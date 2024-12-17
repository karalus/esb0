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
package com.artofarc.util;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.WeakHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class StringWrapper {

	private static final ObjectPool<StringWrapper> POOL = new ObjectPool<>(new WeakHashMap<>(1024));

	private final String smallString;
	private final byte[] compressedContent;
	private final Charset charset;

	private StringWrapper(String string) {
		smallString = string;
		compressedContent = null;
		charset = null;
	}

	private StringWrapper(byte[] content, Charset charset) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(out, IOUtils.MTU)) {
			gzipOutputStream.write(content);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		if (out.size() < content.length) {
			compressedContent = out.toByteArray();
			smallString = null;
		} else {
			compressedContent = null;
			smallString = new String(content, charset);
		}
		this.charset = charset;
	}

	public static StringWrapper create(String string) {
		return POOL.intern(new StringWrapper(string));
	}

	public static StringWrapper create(byte[] content, Charset charset) {
		return POOL.intern(new StringWrapper(content, charset));
	}

	@Override
	public int hashCode() {
		// Since StringWrapper objects are not used in HashMaps as keys at runtime we need not to cache hashCode
		return smallString != null ? smallString.hashCode() : Arrays.hashCode(compressedContent);
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (other instanceof StringWrapper) {
			StringWrapper o = (StringWrapper) other;
			if (smallString != null) {
				return smallString.equals(o.smallString);
			} else if (charset == o.charset) {
				return Arrays.equals(compressedContent, o.compressedContent);
			}
		}
		return false;
	}

	public boolean isEmpty() {
		return smallString != null && smallString.isEmpty();
	}

	public String getString() {
		if (smallString != null) {
			return smallString;
		}
		try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(compressedContent), IOUtils.MTU)) {
			return IOUtils.toString(gzipInputStream, charset);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

}
