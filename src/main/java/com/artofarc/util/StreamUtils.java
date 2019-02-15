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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;

public final class StreamUtils {

	public static final int MTU = Integer.parseInt(System.getProperty("esb0.internalMTU", "4096"));

	public static void copy(InputStream is, OutputStream os) throws IOException {
		final byte[] buffer = new byte[MTU];
		int len;
		while ((len = is.read(buffer)) >= 0) {
			os.write(buffer, 0, len);
		}
	}

	public static void copy(Reader is, Writer os) throws IOException {
		final char[] buffer = new char[MTU];
		int len;
		while ((len = is.read(buffer)) >= 0) {
			os.write(buffer, 0, len);
		}
	}

	public static byte[] copy(InputStream is) throws IOException {
		ByteArrayOutputStream os = new ByteArrayOutputStream(MTU);
		copy(is, os);
		return os.toByteArray();
	}

}
