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
package com.artofarc.esb.artifact;

import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.zip.CRC32;

import javax.sql.DataSource;

public class FileSystemDB extends FileSystem {

	private final static String FILESYSTEM_TABLE = System.getProperty("esb0.filesystem.dbtable", "FILESYSTEM");

	private final DataSource _dataSource;

	public FileSystemDB(DataSource dataSource) {
		_dataSource = dataSource;
	}

	protected FileSystemDB(FileSystemDB fileSystem) {
		super(fileSystem);
		_dataSource = fileSystem._dataSource;
	}

	@Override
	public FileSystemDB copy() {
		return new FileSystemDB(this);
	}

	private static byte[] getBytes(Blob blob) throws SQLException {
		byte[] ba = blob.getBytes(1, (int) blob.length());
		blob.free();
		return ba;
	}

	@Override
	protected byte[] reloadContent(String uri) throws SQLException {
		String sql = "select CONTENT from " + FILESYSTEM_TABLE + " where URI=? and ENVIRONMENT='" + environment + "'";
		try (Connection conn = _dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, uri);
			ResultSet resultSet = ps.executeQuery();
			if (resultSet.next()) {
				return getBytes(resultSet.getBlob(1));
			} else {
				throw new IllegalStateException("FileSystem corrupted. Not found " + uri);
			}
		}
	}

	@Override
	public void parse() throws SQLException {
		CRC32 crc = new CRC32();
		String sql = "select URI, MODIFIED, CONTENT from " + FILESYSTEM_TABLE + " where ENVIRONMENT='" + environment + "'";
		try (Connection conn = _dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet resultSet = ps.executeQuery()) {
			while (resultSet.next()) {
				String uri = resultSet.getString(1).substring(1);
				Artifact artifact = createArtifact(uri);
				if (artifact != null) {
					artifact.setModificationTime(resultSet.getLong(2));
					artifact.setContent(getBytes(resultSet.getBlob(3)));
					crc.update(artifact.getContent());
					artifact.setCrc(crc.getValue());
					crc.reset();
				} else {
					throw new IllegalStateException("Artifact could not be created: " + uri);
				}
			}
		}
	}

	@Override
	public void writeBackChanges() throws SQLException {
		String createSql = "insert into " + FILESYSTEM_TABLE + " (URI, ENVIRONMENT, MODIFIED, CONTENT) VALUES (?,'" + environment + "',?,?)";
		String updateSql = "update " + FILESYSTEM_TABLE + " set MODIFIED=?, CONTENT=? WHERE URI=? and ENVIRONMENT='" + environment + "'";
		String deleteSql = "delete from " + FILESYSTEM_TABLE + " WHERE URI=? and ENVIRONMENT='" + environment + "'";
		try (Connection conn = _dataSource.getConnection();
				PreparedStatement create = conn.prepareStatement(createSql);
				PreparedStatement update = conn.prepareStatement(updateSql);
				PreparedStatement delete = conn.prepareStatement(deleteSql)) {

			for (Map.Entry<String, ChangeType> entry : _changes.entrySet()) {
				Artifact artifact = getArtifact(entry.getKey());
				if (!(artifact instanceof Directory)) {
					switch (entry.getValue()) {
					case CREATE:
						// delete first, if another ESB0 has created it meanwhile
						delete.setString(1, artifact.getURI());
						delete.executeUpdate();
						create.setString(1, artifact.getURI());
						create.setLong(2, artifact.getModificationTime());
						create.setBinaryStream(3, artifact.getContentAsStream());
						create.executeUpdate();
						break;
					case UPDATE:
						update.setLong(1, artifact.getModificationTime());
						update.setBinaryStream(2, artifact.getContentAsStream());
						update.setString(3, artifact.getURI());
						update.executeUpdate();
						break;
					case DELETE:
						delete.setString(1, artifact.getURI());
						delete.executeUpdate();
						break;
					}
				}
			}
		}
		_changes.clear();
		dehydrateArtifacts(_root);
	}

}
