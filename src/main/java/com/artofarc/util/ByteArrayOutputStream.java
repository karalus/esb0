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

import java.nio.ByteBuffer;

public final class ByteArrayOutputStream extends java.io.ByteArrayOutputStream {

	public ByteArrayOutputStream() {
		super(IOUtils.MTU);
	}

	@Override
	public void write(byte[] b, int off, int len) {
		if (count + len > buf.length) {
			super.write(b, off, len);
		} else {
			System.arraycopy(b, off, buf, count, len);
			count += len;
		}
	}

	public ByteArrayInputStream getByteArrayInputStream() {
		return new ByteArrayInputStream(buf, 0, count);
	}

	public ByteBuffer toByteBuffer() {
		return ByteBuffer.wrap(buf, 0, count);
	}

	@Override
	public byte[] toByteArray() {
		return IOUtils.toByteArray(buf, 0, count);
	}

}
