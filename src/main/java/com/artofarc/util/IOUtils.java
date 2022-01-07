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
package com.artofarc.util;

import java.io.*;
import java.util.Formatter;

public final class IOUtils {

	public static final int MTU = Integer.parseInt(System.getProperty("esb0.internalMTU", "8192"));

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

	public interface PredictableInputStream {
		long length();
	}

	public static class PredictableFileInputStream extends FileInputStream implements PredictableInputStream {
		private final File _file;

		public PredictableFileInputStream(File file) throws FileNotFoundException {
			super(file);
			_file = file;
		}

		@Override
		public long length() {
			return _file.length();
		}
	}

	public static void copy(InputStream is, OutputStream os) throws IOException {
		if (is instanceof ByteArrayInputStream) {
			ByteArrayInputStream bis = (ByteArrayInputStream) is;
			bis.copyTo(os);
		} else {
			final byte[] buffer = new byte[MTU];
			int len;
			while ((len = is.read(buffer)) >= 0) {
				os.write(buffer, 0, len);
			}
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
		if (is instanceof ByteArrayInputStream) {
			ByteArrayInputStream bis = (ByteArrayInputStream) is;
			return bis.toByteArray();
		}
		if (is instanceof java.io.ByteArrayInputStream) {
			return ByteArrayInputStream.toByteArray(ReflectionUtils.getField(is, "buf"), ReflectionUtils.getField(is, "pos"), ReflectionUtils.getField(is, "count"));
		}
		if (is instanceof PredictableInputStream) {
			final byte[] ba = new byte[Math.toIntExact(((PredictableInputStream) is).length())];
			readFully(is, ba);
			return ba;
		}
		final ByteArrayOutputStream os = new ByteArrayOutputStream();
		copy(is, os);
		return os.toByteArray();
	}

	public static void readFully(InputStream is, byte ba[]) throws IOException {
		int n = 0, len = ba.length;
		while (n < len) {
			final int count = is.read(ba, n, len - n);
			if (count < 0) throw new EOFException();
			n += count;
		}
	}

	public static byte[] readFile(File file) throws IOException {
		try (InputStream is = new FileInputStream(file)) {
			final byte[] ba = new byte[Math.toIntExact(file.length())];
			readFully(is, ba);
			return ba;
		}
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
		formatter.close();
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
