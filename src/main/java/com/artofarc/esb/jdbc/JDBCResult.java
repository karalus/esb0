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
package com.artofarc.esb.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class JDBCResult implements AutoCloseable {

	public static final String SQL_UPDATE_COUNT = "sqlUpdateCount";

	private final Statement _statement;
	private final int firstUpdateCount;
	private int currentUpdateCount = -1;
	private ResultSet currentResultSet;
	private Boolean readAhead;

	public JDBCResult(Statement statement) throws SQLException {
		_statement = statement;
		currentResultSet = statement.getResultSet();
		if (currentResultSet == null) {
			firstUpdateCount = statement.getUpdateCount();
			readAhead = firstUpdateCount >= 0 && next();
		} else {
			firstUpdateCount = -1;
		}
	}

	public boolean next() throws SQLException {
		if (readAhead != null) {
			boolean next = readAhead;
			readAhead = null;
			return next;
		}
		if (_statement.getMoreResults()) {
			currentResultSet = _statement.getResultSet();
			currentUpdateCount = -1;
			return true;
		} else {
			currentResultSet = null;
			currentUpdateCount = _statement.getUpdateCount();
			return currentUpdateCount >= 0;
		}
	}

	public int getCurrentUpdateCount() {
		if (readAhead != null) {
			return firstUpdateCount;
		}
		return currentUpdateCount;
	}

	public ResultSet getCurrentResultSet() {
		return currentResultSet;
	}

	public boolean hasComplexContent() {
		return currentResultSet != null || readAhead;
	}

	@Override
	public void close() throws SQLException {
		_statement.close();
	}

}
