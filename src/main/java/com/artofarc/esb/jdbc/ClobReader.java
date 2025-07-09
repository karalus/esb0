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

import java.io.FilterReader;
import java.io.IOException;
import java.sql.Clob;
import java.sql.SQLException;

public class ClobReader extends FilterReader {

	private final Clob _clob;

	public ClobReader(Clob clob) throws SQLException {
		super(clob.getCharacterStream());
		_clob = clob;
	}

	@Override
	public void close() throws IOException {
		super.close();
		try {
			_clob.free();
		} catch (SQLException e) {
			throw new IOException(e);
		}
	}

}
