/*
 * Copyright 2023 Andre Karalus
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
package com.artofarc.esb.jms;

import java.io.IOException;
import java.io.InputStream;

import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;

public final class BytesMessageInputStream extends InputStream implements com.artofarc.util.IOUtils.PredictableInputStream {

	private final BytesMessage _bytesMessage;
	private final long length; 
	private long available, markpos = -1;

	public BytesMessageInputStream(BytesMessage bytesMessage) throws JMSException {
		_bytesMessage = bytesMessage;
		available = length = bytesMessage.getBodyLength();
	}

	@Override
	public long length() {
		return available;
	}

	@Override
	public int available() {
		return available > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) available;
	}

	@Override
	public boolean markSupported() {
		return true;
	}

	@Override
	public void mark(int readlimit) {
		markpos = length - available;
	}

	@Override
	public void reset() throws IOException {
		if (markpos < 0) {
			throw new IOException("Resetting to invalid mark");
		}
		try {
			_bytesMessage.reset();
		} catch (JMSException e) {
			throw new IOException(e);
		}
		available = length;
		if (skip(markpos) != markpos) {
			throw new IOException("Resetting to mark did not work");
		}
	}

	@Override
	public int read() throws IOException {
		if (available == 0) {
			return -1;
		}
		try {
			final int c = _bytesMessage.readUnsignedByte();
			--available;
			return c;
		} catch (JMSException e) {
			throw new IOException(e);
		}
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		if (available == 0) {
			return -1;
		}
		try {
			if (off == 0) {
				len = _bytesMessage.readBytes(b, len);
			} else {
				final byte[] ba = new byte[len];
				len = _bytesMessage.readBytes(ba, len);
				if (len > 0) {
					System.arraycopy(ba, 0, b, off, len);
				}
			}
			if (len > 0 && (available -= len) == 0 && markpos < 0) {
				_bytesMessage.clearBody();
			}
			return len;
		} catch (JMSException e) {
			throw new IOException(e);
		}
	}

	@Override
	public void close() throws IOException {
		available = 0;
		try {
			_bytesMessage.clearBody();
		} catch (JMSException e) {
			throw new IOException(e);
		}
	}

}
