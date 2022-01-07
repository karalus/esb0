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

import java.io.IOException;
import java.io.OutputStream;

public final class ByteArrayInputStream extends java.io.ByteArrayInputStream implements Cloneable, IOUtils.PredictableInputStream {

	public ByteArrayInputStream(byte[] buf) {
		super(buf);
	}

	public ByteArrayInputStream(byte[] buf, int offset, int length) {
		super(buf, offset, length);
	}

	@Override
	public long length() {
		return count - pos;
	}

	public void copyTo(OutputStream os) throws IOException {
		os.write(buf, pos, count - pos);
	}

	public byte[] toByteArray() {
		return toByteArray(buf, pos, count);
	}

	static byte[] toByteArray(byte buf[], int pos, int count) {
		if (pos == 0 && buf.length == count) {
			return buf;
		}
		final byte[] copy = new byte[count - pos];
		System.arraycopy(buf, pos, copy, 0, count - pos);
		return copy;
	}

	@Override
	public Object clone() throws CloneNotSupportedException {
		return super.clone();
	}

}
