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
package com.artofarc.util;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.wsdl.Binding;
import javax.wsdl.BindingOperation;
import javax.wsdl.Definition;
import javax.wsdl.WSDLException;
import javax.wsdl.extensions.ElementExtensible;
import javax.wsdl.extensions.ExtensibilityElement;
import javax.wsdl.extensions.soap.SOAPBinding;
import javax.wsdl.extensions.soap.SOAPOperation;
import javax.wsdl.extensions.soap12.SOAP12Binding;
import javax.wsdl.extensions.soap12.SOAP12Operation;
import javax.wsdl.factory.WSDLFactory;
import javax.wsdl.xml.WSDLReader;
import javax.xml.namespace.QName;

public final class WSDL4JUtil {

	private static final WSDLFactory wsdlFactory;

	static {
		try {
			wsdlFactory = WSDLFactory.newInstance();
		} catch (WSDLException e) {
			throw new RuntimeException("Cannot initialize wsdl4j", e);
		}
	}

	public static final WSDLReader createWSDLReader(final boolean doNotFollowXSDs) {
		final WSDLReader wsdlReader = wsdlFactory.newWSDLReader();
		wsdlReader.setFeature("javax.wsdl.verbose", false);
		if (doNotFollowXSDs) {
			// Improves performance, but we cannot access inlined Schema elements
			wsdlReader.setFeature("com.ibm.wsdl.parseXMLSchemas", false);
		}
		return wsdlReader;
	}

	public static final <E extends ExtensibilityElement> List<E> getExtensibilityElements(final ElementExtensible parent, final Class<E> cls) {
		final List<E> result = new ArrayList<>();
		for (Object o : parent.getExtensibilityElements()) {
			if (cls.isInstance(o)) {
				result.add(cls.cast(o));
			}
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	public static final <T extends ExtensibilityElement> T getExtensibilityElement(final ElementExtensible elementExtensible, final Class<?>... classes) {
		for (Object extensibilityElement : elementExtensible.getExtensibilityElements()) {
			for (Class<?> cls : classes) {
				if (cls.isInstance(extensibilityElement)) {
					return (T) extensibilityElement;
				}
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	public static final <T> T invokeExtensibilityElement(final ElementExtensible elementExtensible, final String methodName, final Class<?>... classes)
			throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {

		ExtensibilityElement extensibilityElement = getExtensibilityElement(elementExtensible, classes);
		if (extensibilityElement != null) {
			return (T) extensibilityElement.getClass().getMethod(methodName).invoke(extensibilityElement, new Object[0]);
		}
		return null;
	}

	public static final String getSoapActionURI(BindingOperation bindingOperation) {
		try {
			return invokeExtensibilityElement(bindingOperation, "getSoapActionURI", SOAP12Operation.class, SOAPOperation.class);
		} catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
			throw new RuntimeException(e);
		}
	}

	public static final String getSoapBindingTransportURI(Binding binding) {
		try {
			return invokeExtensibilityElement(binding, "getTransportURI", SOAP12Binding.class, SOAPBinding.class);
		} catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
			throw new RuntimeException(e);
		}
	}

	@SuppressWarnings("unchecked")
	public static final List<BindingOperation> getBindingOperations(Definition definition, String transport) {
		final Set<Map.Entry<QName, Binding>> entrySet = definition.getAllBindings().entrySet();
		for (Map.Entry<QName, Binding> entry : entrySet) {
			Binding binding = entry.getValue();
			if (transport == null || transport.equals(getSoapBindingTransportURI(binding))) {
				return (List<BindingOperation>) binding.getBindingOperations();
			}
		}
		return Collections.emptyList();
	}

	public static final Map<String, String> getMapOperation2SoapActionURI(Definition definition, String transport) {
		final Map<String, String> result = new HashMap<>();
		for (BindingOperation bindingOperation : getBindingOperations(definition, transport)) {
			String soapActionURI = WSDL4JUtil.getSoapActionURI(bindingOperation);
			if (soapActionURI != null) {
				result.put(bindingOperation.getName(), soapActionURI);
			}
		}
		return result;
	}

}
