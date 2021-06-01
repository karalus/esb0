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

import java.util.Properties;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.service.DataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class DataSourceArtifact extends AbstractServiceArtifact {

	public final static String FILE_EXTENSION = "dsdef";

	private String _dataSourceName;
	private Properties _properties;

	public DataSourceArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	public final String getDataSourceName() {
		return _dataSourceName;
	}

	public static boolean isDataSource(Object object) {
		return object instanceof HikariDataSource;
	}

	public static void softEvictConnections(Object object) {
		if (object instanceof HikariDataSource) {
			HikariDataSource dataSource = (HikariDataSource) object;
			dataSource.getHikariPoolMXBean().softEvictConnections();
		}
	}

	public static void setMetricRegistry(Object object, Object metricRegistry) {
		if (object instanceof HikariDataSource) {
			HikariDataSource dataSource = (HikariDataSource) object;
			dataSource.setMetricRegistry(metricRegistry);
		}
	}

	public HikariDataSource createDataSource() {
		return new HikariDataSource(new HikariConfig(_properties));
	}

	@Override
	protected DataSourceArtifact clone(FileSystem fileSystem, Directory parent) {
		DataSourceArtifact clone = initClone(new DataSourceArtifact(fileSystem, parent, getName()));
		clone._dataSourceName = _dataSourceName;
		clone._properties = _properties;
		return clone;
	}

	@Override
	protected void validateInternal(GlobalContext globalContext) throws Exception {
		DataSource dataSource = unmarshal();
		_dataSourceName = dataSource.getName();
		_properties = createProperties(dataSource.getProperty(), globalContext);
	}

}
