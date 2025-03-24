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
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;

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
		default long length() {
			return lengthAsInt();
		}

		default int lengthAsInt() {
			return Math.toIntExact(length());
		}

		default ReadableByteChannel getChannel() {
			return Channels.newChannel((InputStream) this);
		}
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

	public static Long getLength(InputStream is) {
		if (is instanceof PredictableInputStream) {
			return ((PredictableInputStream) is).length();
		}
		if (is instanceof java.io.ByteArrayInputStream) {
			return Long.valueOf(((java.io.ByteArrayInputStream) is).available());
		}
		return null;
	}

	static byte[] toByteArray(byte buf[], int pos, int count) {
		if (pos == 0 && buf.length == count) {
			return buf;
		}
		final byte[] copy = new byte[count - pos];
		System.arraycopy(buf, pos, copy, 0, count - pos);
		return copy;
	}

	public static byte[] toByteArray(InputStream is) throws IOException {
		if (is instanceof ByteArrayInputStream) {
			ByteArrayInputStream bis = (ByteArrayInputStream) is;
			return bis.toByteArray();
		}
		if (is instanceof java.io.ByteArrayInputStream) {
			ExtractByteArray extractor = new ExtractByteArray();
			is.transferTo(extractor);
			return toByteArray(extractor.buf, extractor.offset, extractor.length);
		}
		if (is instanceof PredictableInputStream) {
			return toByteArray(is, ((PredictableInputStream) is).lengthAsInt());
		}
		final ByteArrayOutputStream os = new ByteArrayOutputStream();
		is.transferTo(os);
		return os.toByteArray();
	}

	private static byte[] toByteArray(InputStream is, int length) throws IOException {
		final byte[] ba = new byte[length];
		readFully(is, ba);
		return ba;
	}

	public static ByteBuffer toByteBuffer(InputStream is) throws IOException {
		if (is instanceof ByteArrayInputStream) {
			ByteArrayInputStream bis = (ByteArrayInputStream) is;
			return bis.toByteBuffer();
		}
		if (is instanceof java.io.ByteArrayInputStream) {
			ExtractByteArray extractor = new ExtractByteArray();
			is.transferTo(extractor);
			return ByteBuffer.wrap(extractor.buf, extractor.offset, extractor.length);
		}
		if (is instanceof PredictableInputStream) {
			return toByteBuffer(is, ((PredictableInputStream) is).lengthAsInt());
		}
		final ByteArrayOutputStream os = new ByteArrayOutputStream();
		is.transferTo(os);
		return os.toByteBuffer();
	}

	private static ByteBuffer toByteBuffer(InputStream is, int length) throws IOException {
		ByteBuffer byteBuffer = ByteBuffer.allocate(length);
		for (ReadableByteChannel channel = ((PredictableInputStream) is).getChannel(); channel.read(byteBuffer) >= 0 && byteBuffer.remaining() > 0;);
		if (byteBuffer.remaining() > 0) throw new EOFException();
		return (ByteBuffer) byteBuffer.rewind();
	}

	public static ByteArrayInputStream toByteArrayInputStream(java.io.ByteArrayInputStream bis) throws IOException {
		if (bis instanceof ByteArrayInputStream) {
			return (ByteArrayInputStream) bis;
		}
		ExtractByteArray extractor = new ExtractByteArray();
		bis.transferTo(extractor);
		return new ByteArrayInputStream(extractor.buf, extractor.offset, extractor.length);
	}

	private static class ExtractByteArray extends OutputStream {
		byte[] buf;
		int offset, length;

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			if (buf != null) {
				throw new IOException("Unexpected behavior. Only one single call with complete buffer is expected.");
			}
			buf = b;
			offset = off;
			length = len;
		}

		@Override
		public void write(int b) throws IOException {
			throw new IOException("Unexpected behavior. Only one single call with complete buffer is expected.");
		}
	}

	public static String toString(InputStream is, Charset charset) throws IOException {
		try (StringBuilderWriter writer = new StringBuilderWriter()) {
			new InputStreamReader(is, charset).transferTo(writer);
			return writer.toString();
		}
	}

	public static int indexOf(CharSequence chars, String str, int fromIndex) {
		final char first = str.charAt(0);
		final int max = chars.length() - str.length();
		for (int i = fromIndex; i <= max; ++i) {
			// Look for first character
			if (chars.charAt(i) != first) {
				while (++i <= max && chars.charAt(i) != first);
			}
			if (i <= max) {
				// now look at the rest
				int j = i + 1;
				int end = i + str.length();
				for (int k = 1; j < end && chars.charAt(j) == str.charAt(k); j++, k++);
				if (j == end) {
					return i;
				}
			}
		}
		return -1;
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
		try (InputStream is = new PredictableFileInputStream(file)) {
			return toByteArray(is);
		}
	}

	public static String convertToHexDump(byte[] ba) throws IOException {
		return convertToHexDump(new ByteArrayInputStream(ba));
	}

	public static String convertToHexDump(InputStream is) throws IOException {
		int pos = 0;
		int c;
		StringBuilder text = new StringBuilder();
		StringBuilder result = new StringBuilder();
		String lineSeparator = System.getProperty("line.separator");

		while ((c = is.read()) >= 0) {
			result.append(URLUtils.convertNibble2Char(c >> 4)).append(URLUtils.convertNibble2Char(c)).append(' ');
			// If the character is not printable, just print a dot symbol "."
			if (Character.isISOControl(c)) {
				text.append('.');
			} else {
				text.append((char) c);
			}
			if (++pos == 16) {
				result.append("   ").append(text).append(lineSeparator);
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
			result.append("   ").append(text).append(lineSeparator);
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
