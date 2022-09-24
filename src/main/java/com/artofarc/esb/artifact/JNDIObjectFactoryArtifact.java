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

	private String _jndiName, _adminPostAction;
	private ObjectFactory _objectFactory;
	private Reference _reference;
	private DeploymentSupport _deploymentSupport;

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
		JndiObjectFactory jndiObjectFactory = unmarshal();
		_jndiName = jndiObjectFactory.getName();
		ClassLoader classLoader = resolveClassLoader(globalContext, jndiObjectFactory.getClassLoader());
		_objectFactory = (ObjectFactory) classLoader.loadClass(jndiObjectFactory.getFactory()).newInstance();
		Properties properties = createProperties(jndiObjectFactory.getProperty(), globalContext);
		_reference = new Reference(jndiObjectFactory.getType());
		for (String key : properties.stringPropertyNames()) {
			_reference.add(new StringRefAddr(key, (String) properties.get(key)));
		}
		_adminPostAction = jndiObjectFactory.getAdminPostAction();
		if (jndiObjectFactory.getDeploymentSupportClass() != null) {
			_deploymentSupport = (DeploymentSupport) classLoader.loadClass(jndiObjectFactory.getDeploymentSupportClass()).newInstance();
		}
	}

	@Override
	protected void clearContent() {
		super.clearContent();
		_objectFactory = null;
		_reference = null;
		_deploymentSupport = null;
	}

	public String getJndiName() {
		return _jndiName;
	}

	public String getAdminPostAction() {
		return _adminPostAction;
	}

	public boolean tryUpdate(Object oldObject, Object newObject) {
		return _deploymentSupport != null ? _deploymentSupport.tryUpdate(oldObject, newObject) : false;
	}

	public Object createObject() throws Exception {
		return _objectFactory.getObjectInstance(_reference, null, null, null);
	}

	public interface DeploymentSupport {
		boolean tryUpdate(Object oldObject, Object newObject);
	}

}
