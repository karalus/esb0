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
package com.artofarc.esb.artifact;

import java.util.Properties;

import javax.naming.Reference;
import javax.naming.StringRefAddr;
import javax.naming.spi.ObjectFactory;

import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.service.JndiObjectFactory;

public final class JNDIObjectFactoryArtifact extends AbstractServiceArtifact {

	public final static String FILE_EXTENSION = "xjndi";

	private String _jndiName, _type, _adminPostAction;
	private ObjectFactory _objectFactory;
	private Factory _factory;
	private Properties _properties;

	public JNDIObjectFactoryArtifact(FileSystem fileSystem, Directory parent, String name) {
		super(fileSystem, parent, name);
	}

	@Override
	protected JNDIObjectFactoryArtifact clone(FileSystem fileSystem, Directory parent) {
		JNDIObjectFactoryArtifact clone = initClone(new JNDIObjectFactoryArtifact(fileSystem, parent, getName()));
		clone._jndiName = _jndiName;
		clone._adminPostAction = _adminPostAction;
		return clone;
	}

	@Override
	protected void validateInternal(GlobalContext globalContext) throws Exception {
		JndiObjectFactory jndiObjectFactory = unmarshal(globalContext);
		ClassLoader classLoader = resolveClassLoader(globalContext, jndiObjectFactory.getClassLoader());
		_type = jndiObjectFactory.getType();
		if (_type != null) {
			classLoader.loadClass(_type);
		}
		_properties = createProperties(jndiObjectFactory.getProperty(), globalContext);
		if (jndiObjectFactory.getEsb0Factory() != null) {
			_factory = (Factory) classLoader.loadClass(jndiObjectFactory.getEsb0Factory()).newInstance();
			_factory.validate(_type, _properties);
		} else if (jndiObjectFactory.getFactory() != null) {
			_objectFactory = (ObjectFactory) classLoader.loadClass(jndiObjectFactory.getFactory()).newInstance();
		} else {
			throw new ValidationException(this, "Either objectFactory or esb0Factory must be set");
		}
		_jndiName = jndiObjectFactory.getName();
		_adminPostAction = jndiObjectFactory.getAdminPostAction();
	}

	public String getJndiName() {
		return _jndiName;
	}

	public String getAdminPostAction() {
		return _adminPostAction;
	}

	public boolean tryUpdate(Object oldObject) {
		boolean successful = false;
		if (_factory != null && (successful = _factory.tryUpdate(oldObject))) {
			_factory = null;
			_type = null;
			_properties = null;
		}
		return successful;
	}

	public Object createObject() throws ValidationException {
		Object object;
		if (_factory != null) {
			object = _factory.createObject();
			_factory = null;
		} else {
			Reference reference = new Reference(_type);
			for (String key : _properties.stringPropertyNames()) {
				reference.add(new StringRefAddr(key, (String) _properties.get(key)));
			}
			try {
				object = _objectFactory.getObjectInstance(reference, null, null, null);
			} catch (Exception e) {
				throw new ValidationException(this, e);
			}
			_objectFactory = null;
		}
		_type = null;
		_properties = null;
		return object;
	}

	public interface Factory {
		void validate(String type, Properties properties);
		boolean tryUpdate(Object oldObject);
		Object createObject();
	}

}
