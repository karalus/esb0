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
package com.artofarc.esb.jdbc;

import java.io.FilterInputStream;
import java.io.IOException;
import java.sql.Blob;
import java.sql.SQLException;

public class BlobInputStream extends FilterInputStream {

	private final Blob _blob;

	public BlobInputStream(Blob blob) throws SQLException {
		super(blob.getBinaryStream());
		_blob = blob;
	}

	@Override
	public void close() throws IOException {
		super.close();
		try {
			_blob.free();
		} catch (SQLException e) {
			throw new IOException(e);
		}
	}

}
