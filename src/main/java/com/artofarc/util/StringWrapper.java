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
package com.artofarc.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class StringWrapper {

	public static final Charset CHARSET_DEFAULT = java.nio.charset.StandardCharsets.UTF_8;

	private final String smallString;
	private final byte[] compressedContent;

	public StringWrapper(String string) {
		smallString = string.intern();
		compressedContent = null;
	}

	public StringWrapper(byte[] content) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		GZIPOutputStream gzipOutputStream = new GZIPOutputStream(out, IOUtils.MTU);
		gzipOutputStream.write(content);
		gzipOutputStream.close();
		compressedContent = out.toByteArray();
		smallString = null;
	}

	public boolean isEmpty() {
		return smallString != null && smallString.isEmpty();
	}

	public String getString() {
		if (smallString != null) {
			return smallString;
		}
		try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(compressedContent), IOUtils.MTU)) {
			return new String(IOUtils.copy(gzipInputStream), CHARSET_DEFAULT);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

}
