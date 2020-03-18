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

import java.io.*;
import java.util.Formatter;

public final class IOUtils {

	public static final int MTU = Integer.parseInt(System.getProperty("esb0.internalMTU", "4096"));

	public static final String FILE_EXTENSION_XML_DOC = "xml";

	public static final class PreventFlushOutputStream extends FilterOutputStream {

		public PreventFlushOutputStream(OutputStream out) {
			super(out);
		}

		@Override
		public void write(byte b[], int off, int len) throws IOException {
			out.write(b, off, len);
		}

		@Override
		public void flush() {
			// don't flush, wait until close()
		}

		@Override
		public void close() throws IOException {
			out.close();
		}
	}

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
		final ByteArrayOutputStream os = new ByteArrayOutputStream();
		copy(is, os);
		return os.toByteArray();
	}

	public static byte[] readFile(final File file) throws IOException {
		final byte[] ba = new byte[(int) file.length()];
		try (final DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
			dis.readFully(ba);
		}
		return ba;
	}

	public static InputStream getResourceAsStream(String name) throws FileNotFoundException {
		InputStream stream = IOUtils.class.getClassLoader().getResourceAsStream(name);
		if (stream == null) {
			throw new FileNotFoundException(name + " must be in classpath");
		}
		return stream;
	}

	public static String convertToHexDump(InputStream is) throws IOException {
		int pos = 0;
		int c;
		StringBuilder text = new StringBuilder();
		StringBuilder result = new StringBuilder();
		Formatter formatter = new Formatter(result);

		while ((c = is.read()) >= 0) {
			// convert to hex value with "X" formatter
			formatter.format("%02X ", c);

			// If the character is not printable, just print a dot symbol "."
			if (Character.isISOControl(c)) {
				text.append('.');
			} else {
				text.append((char) c);
			}
			if (++pos == 16) {
				result.append("   ").append(text).append(System.getProperty("line.separator"));
				text.setLength(0);
				pos = 0;
			}
		}
		// remaining content
		if (pos != 0) {
			// add spaces for formatting purpose
			for (; pos < 16; ++pos) {
				result.append("   ");
			}
			result.append("   ").append(text).append(System.getProperty("line.separator"));
		}
		is.close();
		return result.toString();
	}

	public static String getExt(String name) {
		int i = name.lastIndexOf('.');
		return i < 0 ? "" : name.substring(i + 1);
	}

	public static String stripExt(String name) {
		int i = name.lastIndexOf('.');
		return i > 0 ? name.substring(0, i) : name;
	}

}
