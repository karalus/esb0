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
package com.artofarc.esb.artifact;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.Properties;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.service.DataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;

public final class DataSourceArtifact extends AbstractServiceArtifact {

	public final static String FILE_EXTENSION = "dsdef";

	private String _dataSourceName;
	private HikariDataSourceFactory _hikariDataSourceFactory;

	public DataSourceArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	public final String getDataSourceName() {
		return _dataSourceName;
	}

	public HikariDataSource createDataSource() {
		return _hikariDataSourceFactory.createObject();
	}

	@Override
	protected DataSourceArtifact clone(FileSystem fileSystem, Directory parent) {
		DataSourceArtifact clone = initClone(new DataSourceArtifact(fileSystem, parent, getName()));
		clone._dataSourceName = _dataSourceName;
		return clone;
	}

	@Override
	protected void validateInternal(GlobalContext globalContext) throws Exception {
		DataSource dataSource = unmarshal(globalContext);
		_dataSourceName = dataSource.getName();
		_hikariDataSourceFactory = new HikariDataSourceFactory();
		_hikariDataSourceFactory.validate(null, createProperties(dataSource.getProperty(), globalContext));
	}

	boolean tryUpdate(Object other) {
		return _hikariDataSourceFactory.tryUpdate(other);
	}

	public static class HikariDataSourceFactory implements JNDIObjectFactoryArtifact.Factory {

		private final static Object[] EMPTY_OBJECT_ARRAY = new Object[0];

		private HikariConfig _hikariConfig;

		@Override
		public void validate(String type, Properties properties) {
			_hikariConfig = new HikariConfig(properties);
			_hikariConfig.validate();
		}

		@Override
		public boolean tryUpdate(Object oldDataSource) {
			if (oldDataSource instanceof HikariDataSource) {
				outer: for (Method method : HikariConfig.class.getDeclaredMethods()) {
					String name = method.getName();
					if (Modifier.isPublic(method.getModifiers()) && (name.startsWith("get") || name.startsWith("is"))) {
						try {
							Object newValue = method.invoke(_hikariConfig, EMPTY_OBJECT_ARRAY);
							if (!Objects.equals(method.invoke(oldDataSource, EMPTY_OBJECT_ARRAY), newValue)) {
								for (Method methodMX : HikariConfigMXBean.class.getMethods()) {
									if (methodMX.getParameterCount() == 1 && methodMX.getName().endsWith(name.substring(2))) {
										methodMX.invoke(oldDataSource, newValue);
										continue outer;
									}
								}
								return false;
							}
						} catch (ReflectiveOperationException e) {
							throw new RuntimeException(e);
						}
					}
				}
				return true;
			}
			return false;
		}

		@Override
		public HikariDataSource createObject() {
			return new HikariDataSource(_hikariConfig);
		}

	}

}
