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
package com.artofarc.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.wsdl.Binding;
import javax.wsdl.BindingOperation;
import javax.wsdl.Definition;
import javax.wsdl.Message;
import javax.wsdl.Part;
import javax.wsdl.WSDLException;
import javax.wsdl.extensions.ElementExtensible;
import javax.wsdl.extensions.ExtensibilityElement;
import javax.wsdl.extensions.mime.MIMEMultipartRelated;
import javax.wsdl.extensions.schema.Schema;
import javax.wsdl.extensions.soap.SOAPBinding;
import javax.wsdl.extensions.soap.SOAPBody;
import javax.wsdl.extensions.soap.SOAPOperation;
import javax.wsdl.extensions.soap12.SOAP12Binding;
import javax.wsdl.extensions.soap12.SOAP12Body;
import javax.wsdl.extensions.soap12.SOAP12Operation;
import javax.wsdl.factory.WSDLFactory;
import javax.wsdl.xml.WSDLReader;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;

import org.w3c.dom.Element;

public final class WSDL4JUtil {

	private static final WSDLFactory wsdlFactory;

	static {
		try {
			wsdlFactory = WSDLFactory.newInstance();
		} catch (WSDLException e) {
			throw new RuntimeException("Cannot initialize wsdl4j", e);
		}
	}

	public static WSDLReader createWSDLReader(final boolean doNotFollowXSDs) {
		final WSDLReader wsdlReader = wsdlFactory.newWSDLReader();
		wsdlReader.setFeature("javax.wsdl.verbose", false);
		if (doNotFollowXSDs) {
			// Improves performance, but we cannot access inlined Schema elements
			wsdlReader.setFeature("com.ibm.wsdl.parseXMLSchemas", false);
		}
		return wsdlReader;
	}

	public static <E extends ExtensibilityElement> List<E> getExtensibilityElements(final ElementExtensible parent, final Class<E> cls) {
		final List<E> result = new ArrayList<>();
		for (Object o : parent.getExtensibilityElements()) {
			if (cls.isInstance(o)) {
				result.add(cls.cast(o));
			}
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	public static <T extends ExtensibilityElement> T getExtensibilityElement(final ElementExtensible elementExtensible, final Class<?>... classes) {
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
	private static <T> T invokeExtensibilityElement(final ElementExtensible elementExtensible, final String methodName, final Class<?>... classes) throws ReflectiveOperationException {
		ExtensibilityElement extensibilityElement = getExtensibilityElement(elementExtensible, classes);
		if (extensibilityElement != null) {
			return (T) extensibilityElement.getClass().getMethod(methodName).invoke(extensibilityElement, new Object[0]);
		}
		return null;
	}

	public static String getSOAPActionURI(BindingOperation bindingOperation) {
		try {
			return invokeExtensibilityElement(bindingOperation, "getSoapActionURI", SOAPOperation.class, SOAP12Operation.class);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	public static String getSOAPBindingTransportURI(Binding binding) {
		try {
			return invokeExtensibilityElement(binding, "getTransportURI", SOAPBinding.class, SOAP12Binding.class);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	public static boolean isSOAPBindingRPCStyle(Binding binding) {
		try {
			return "rpc".equals(invokeExtensibilityElement(binding, "getStyle", SOAPBinding.class, SOAP12Binding.class));
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	public static Binding getBinding(Map<QName, Binding> allBindings, String bindingName, String transport) {
		Binding result = null;
		for (Map.Entry<QName, Binding> entry : allBindings.entrySet()) {
			if (bindingName == null || bindingName.equals(entry.getKey().getLocalPart())) {
				Binding binding = entry.getValue();
				if (transport == null || transport.equals(getSOAPBindingTransportURI(binding))) {
					if (result != null) {
						// Not unique -> no result
						result = null;
						break;
					}
					result = binding;
				}
			}
		}
		return result;
	}

	public static Map<String, String> getMapOperation2SoapActionURI(List<BindingOperation> bindingOperations) {
		final Map<String, String> result = DataStructures.createHashMap(bindingOperations.size());
		for (BindingOperation bindingOperation : bindingOperations) {
			String soapActionURI = WSDL4JUtil.getSOAPActionURI(bindingOperation);
			if (soapActionURI != null) {
				result.put(bindingOperation.getName(), soapActionURI);
			}
		}
		return result;
	}

	public static boolean hasSOAP11Binding(Map<QName, Binding> allBindings) {
		for (Map.Entry<QName, Binding> entry : allBindings.entrySet()) {
			Binding binding = entry.getValue();
			if (getExtensibilityElement(binding, SOAPBinding.class) != null) {
				return true;
			}
		}
		return false;
	}

	public static boolean hasSOAP12Binding(Map<QName, Binding> allBindings) {
		for (Map.Entry<QName, Binding> entry : allBindings.entrySet()) {
			Binding binding = entry.getValue();
			if (getExtensibilityElement(binding, SOAP12Binding.class) != null) {
				return true;
			}
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	public static QName getInputElementQName(BindingOperation bindingOperation, boolean soap12) {
		ElementExtensible bindingInput = bindingOperation.getBindingInput();
		// According to http://www.ws-i.org/Profiles/AttachmentsProfile-1.0-2004-08-24.html		
		MIMEMultipartRelated mmr = getExtensibilityElement(bindingInput, MIMEMultipartRelated.class);
		if (mmr != null) {
			bindingInput = (ElementExtensible) mmr.getMIMEParts().get(0);
		}
		List<String> parts;
		if (soap12) {
			SOAP12Body body = getExtensibilityElement(bindingInput, SOAP12Body.class);
			if (body == null) {
				throw new IllegalArgumentException("BindingOperation has no SOAP 1.2 Body: " + bindingOperation.getName());
			}
			parts = body.getParts();
		} else {
			SOAPBody body = getExtensibilityElement(bindingInput, SOAPBody.class);
			if (body == null) {
				throw new IllegalArgumentException("BindingOperation has no SOAP 1.1 Body: " + bindingOperation.getName());
			}
			parts = body.getParts();
		}
		Message message = bindingOperation.getOperation().getInput().getMessage();
		Part part = parts == null || parts.isEmpty() ? (Part) message.getParts().values().iterator().next() : message.getPart(parts.get(0));
		return part.getElementName();
	}

	public static <E extends Exception> void processSchemas(Definition definition, ThrowingConsumer<Element, E> consumer) throws E {
		if (definition.getTypes() != null) {
			for (Schema schema : getExtensibilityElements(definition.getTypes(), Schema.class)) {
				Element schemaElement = schema.getElement();
				@SuppressWarnings("unchecked")
				Map<String, String> namespaces = definition.getNamespaces();
				// add outer ns prefixes that might be needed within schema element
				for (Map.Entry<String, String> entry : namespaces.entrySet()) {
					String attrName = entry.getKey().isEmpty() ? "xmlns" : "xmlns:" + entry.getKey();
					if (!schemaElement.hasAttribute(attrName)) {
						schemaElement.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, attrName, entry.getValue());
					}
				}
				consumer.accept(schemaElement);
			}
		}
	}

	@FunctionalInterface
	public interface ThrowingConsumer<T, E extends Exception> {
		void accept(T t) throws E;
	}

}
