/*
 * Copyright 2025 Andre Karalus
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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * This FileInputStream creates the needed {@link java.io.FileDescriptor} lazily (useful for HEAD requests).<p>
 * Buffering (like {@link java.io.BufferedInputStream}) when only small chunks are read is already included. The buffer is also allocated lazily.
 */
public class FileInputStream extends InputStream implements IOUtils.PredictableInputStream {

	private final File _file;
	private InputStream fileInputStream;
	private byte[] buf;
	private int count, pos;
	private long available;

	public FileInputStream(File file) throws FileNotFoundException {
		if (!file.isFile()) {
			throw new FileNotFoundException("Not a file " + file);
		}
		available = file.length();
		_file = file;
	}

	private InputStream getFileInputStream() throws FileNotFoundException {
		if (fileInputStream == null) {
			fileInputStream = new java.io.FileInputStream(_file);
		}
		return fileInputStream;
	}

	private int fillBuffer() throws IOException {
		if (buf == null) {
			buf = new byte[IOUtils.MTU];
		}
		count = getFileInputStream().read(buf, pos = 0, IOUtils.MTU);
		if (count > 0) available -= count;
		return count;
	}

	@Override
	public long length() {
		int avail = count - pos;
		return avail <= 0 ? available : available + avail;
	}

	@Override
	public int read() throws IOException {
		if (pos >= count) {
			if (fillBuffer() <= 0) return -1;
		}
		return buf[pos++] & 0xff;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		int avail = count - pos;
		if (avail <= 0) {
			// Only buffer when small chunks are read
			if (len >= IOUtils.MTU || len >= available) {
				int l = getFileInputStream().read(b, off, len);
				if (l > 0) available -= l;
				return l;
			}
			avail = fillBuffer();
			if (avail <= 0) return -1;
		}
		int cnt = avail < len ? avail : len;
		System.arraycopy(buf, pos, b, off, cnt);
		pos += cnt;
		return cnt;
	}

	@Override
	public long skip(long n) throws IOException {
		if (n <= 0) return 0;
		long remaining = n;
		int avail = count - pos;
		if (avail > 0) {
			int cnt = avail < n ? avail : (int) n;
			pos += cnt;
			remaining -= cnt;
		}
		if (remaining > 0) {
			long l = getFileInputStream().skip(remaining);
			if (l > 0) available -= l;
			remaining -= l;
		}
		return n - remaining;
	}

	@Override
	public void close() throws IOException {
		if (fileInputStream != null) {
			fileInputStream.close();
		}
	}

}
