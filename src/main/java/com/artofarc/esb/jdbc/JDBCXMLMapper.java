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

import java.sql.Array;
import java.sql.Blob;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Struct;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.List;

import javax.mail.internet.MimeBodyPart;
import javax.xml.bind.JAXBElement;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.namespace.QName;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;

import org.eclipse.persistence.dynamic.DynamicEntity;
import org.eclipse.persistence.dynamic.DynamicHelper;
import org.eclipse.persistence.dynamic.DynamicType;
import org.eclipse.persistence.jaxb.dynamic.DynamicJAXBContext;
import org.eclipse.persistence.mappings.DatabaseMapping;
import org.eclipse.persistence.oxm.XMLDescriptor;
import org.eclipse.persistence.oxm.XMLField;
import org.eclipse.persistence.oxm.mappings.XMLChoiceObjectMapping;
import org.eclipse.persistence.oxm.mappings.XMLCompositeCollectionMapping;
import org.eclipse.persistence.oxm.mappings.XMLCompositeObjectMapping;
import org.w3c.dom.Node;

import com.artofarc.esb.message.ESBMessage;
import com.artofarc.util.StreamUtils;

public final class JDBCXMLMapper {

	private final static DatatypeFactory datatypeFactory;

	static {
		try {
			datatypeFactory = DatatypeFactory.newInstance();
		} catch (javax.xml.datatype.DatatypeConfigurationException e) {
			throw new RuntimeException(e);
		}
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	public static JAXBElement<?> createJAXBElement(String namespace, String name, Object object) {
		return new JAXBElement(new QName(namespace, name), object.getClass(), object);
	}

	private final DynamicJAXBContext _jaxbContext;

	public JDBCXMLMapper(DynamicJAXBContext jaxbContext) {
		_jaxbContext = jaxbContext;
	}

	public DynamicJAXBContext getJAXBContext() {
		return _jaxbContext;
	}

	public DynamicEntity createDynamicEntity(String namespace, String typeName, boolean isGlobalType) {
		Object entity = _jaxbContext.createByQualifiedName(namespace, typeName, isGlobalType);
		if (entity == null) {
			throw new RuntimeException("Cannot find complex type for " + typeName + " in " + namespace);
		}
		return (DynamicEntity) entity;
	}

	public static Object toJDBC(DynamicEntity entity, boolean root, JDBCConnection connection) throws SQLException {
		DynamicType dynamicType = DynamicHelper.getType(entity);
		XMLDescriptor descriptor = (XMLDescriptor) dynamicType.getDescriptor();
		QName typeName = descriptor.getSchemaReference().getSchemaContextAsQName();
		List<String> propertiesNames = dynamicType.getPropertiesNames();
		boolean isArray = false;
		if (propertiesNames.size() == 1) {
			DatabaseMapping mapping = descriptor.getMappingForAttributeName(propertiesNames.get(0));
			isArray = mapping.isCollectionMapping();
			// for eclipselink 2.6.x, 2.7.0, 2.7.1
			isArray |= mapping.getAttributeClassification() != null && List.class.isAssignableFrom(mapping.getAttributeClassification());
		}
		if (isArray) {
			Object property = entity.get(propertiesNames.get(0));
			if (property instanceof List<?>) {
				List<?> array = (List<?>) property;
				Object[] elements = new Object[array.size()];
				for (int j = 0; j < array.size(); ++j) {
					Object value = array.get(j);
					if (value instanceof DynamicEntity) {
						elements[j] = toJDBC((DynamicEntity) value, false, connection);
					} else {
						elements[j] = toJDBC(value, connection);
					}
				}
				return connection.createArray(typeName.getLocalPart(), elements);
			} else if (property == null) {
				return null;//connection.createArray(typeName.getLocalPart(), new Object[0]);
			} else {
				throw new RuntimeException("Cannot map property to " + typeName + ": " + property);
			}
		} else {
			Object[] attributes = new Object[propertiesNames.size()];
			for (int i = 0; i < propertiesNames.size(); ++i) {
				Object property = entity.get(propertiesNames.get(i));
				if (property instanceof JAXBElement<?>) {
					attributes[i] = toJDBC((JAXBElement<?>) property, false, connection);
				} else if (property instanceof DynamicEntity) {
					attributes[i] = toJDBC((DynamicEntity) property, false, connection);
				} else if (property != null) {
					attributes[i] = toJDBC(property, connection);
				}
			}
			boolean isXmlType = attributes.length == 1 && attributes[0] instanceof SQLXML;
			try {
				return root || isXmlType ? attributes[0] : connection.getConnection().createStruct(typeName.getLocalPart(), attributes);
			} catch (SQLException e) {
				throw new SQLException("Could not create Struct " + typeName.getLocalPart(), e);
			}
		}
	}

	public static Object toJDBC(JAXBElement<?> jaxbElement, boolean root, JDBCConnection connection) throws SQLException {
		Object value = jaxbElement.getValue();
		if (value instanceof DynamicEntity) {
			return toJDBC((DynamicEntity) value, root, connection);
		} else {
			return toJDBC(value, connection);
		}
	}

	public static Object toJDBC(Object value, JDBCConnection connection) throws SQLException {
		if (value instanceof XMLGregorianCalendar) {
			XMLGregorianCalendar calendar = (XMLGregorianCalendar) value;
			return new Timestamp(calendar.toGregorianCalendar(JDBCParameter.TIME_ZONE, null, null).getTimeInMillis());
		}
		if (value instanceof Calendar) {
			Calendar calendar = (Calendar) value;
			return new Timestamp(calendar.getTimeInMillis());
		}
		if (value instanceof Node) {
			SQLXML sqlxml = connection.getConnection().createSQLXML();
			sqlxml.setResult(DOMResult.class).setNode((Node) value);
			return sqlxml;
		}
		if (value instanceof byte[]) {
			Blob blob = connection.getConnection().createBlob();
			blob.setBytes(1, (byte[]) value);
			return blob;
		}
		return value;
	}

	public String getTypeName(String namespace, String elementName) {
		DynamicEntity entity = createDynamicEntity(namespace, elementName, false);
		DynamicType dynamicType = DynamicHelper.getType(entity);
		XMLDescriptor descriptor = (XMLDescriptor) dynamicType.getDescriptor();
		if (descriptor.getDefaultRootElementField() != null) {
			String propName = dynamicType.getPropertiesNames().get(0);
			XMLChoiceObjectMapping mapping = (XMLChoiceObjectMapping) descriptor.getMappingForAttributeName(propName);
			Class<?> inner = mapping.getFieldToClassMappings().get(descriptor.getFields().get(0));
			DynamicType dynamicTypeInner = _jaxbContext.getDynamicType(inner.getName());
			XMLDescriptor descriptorInner = (XMLDescriptor) dynamicTypeInner.getDescriptor();
			QName schemaContext = descriptorInner.getSchemaReference().getSchemaContextAsQName();
			return schemaContext.getLocalPart();
		} else {
			QName schemaContext = descriptor.getSchemaReference().getSchemaContextAsQName();
			return schemaContext.getLocalPart();
		}
	}

	public Object fromJDBC(Struct struct, String namespace, String elementName) throws SQLException {
		DynamicEntity entity = createDynamicEntity(namespace, elementName, false);
		DynamicType dynamicType = DynamicHelper.getType(entity);
		XMLDescriptor descriptor = (XMLDescriptor) dynamicType.getDescriptor();
		DynamicEntity result = fromJDBC(struct, namespace);
		if (descriptor.getDefaultRootElementField() != null) {
			entity.set(dynamicType.getPropertiesNames().get(0), createJAXBElement(namespace, struct.getSQLTypeName(), result));
			return entity;
		} else {
			return createJAXBElement(namespace, elementName, result);
		}
	}

	public DynamicEntity fromJDBC(Struct struct, String namespace) throws SQLException {
		DynamicEntity entity = createDynamicEntity(namespace, struct.getSQLTypeName(), true);
		DynamicType dynamicType = DynamicHelper.getType(entity);
		List<String> propertiesNames = dynamicType.getPropertiesNames();
		Object[] attributes = struct.getAttributes();
		if (attributes.length != propertiesNames.size()) {
			throw new IllegalArgumentException(struct.getSQLTypeName() + " does not match " + dynamicType.getName());
		}
		for (int i = 0; i < attributes.length; ++i) {
			Object attribute = attributes[i];
			String propertyName = propertiesNames.get(i);
			if (attribute instanceof Struct) {
				Struct inner = (Struct) attribute;
				entity.set(propertyName, createJAXBElement(namespace, inner.getSQLTypeName(), fromJDBC(inner, namespace)));
			} else if (attribute instanceof Array) {
				Array inner = (Array) attribute;
				DynamicEntity arrayEntity = createDynamicEntity(namespace, JDBCConnection.getSQLTypeName(inner), true);
				DynamicType dynamicArrayType = DynamicHelper.getType(arrayEntity);
				Object[] elements = (Object[]) inner.getArray();
				List<Object> array = new ArrayList<>(elements.length);
				for (Object element : elements) {
					if (element instanceof Struct) {
						array.add(fromJDBC((Struct) element, namespace));
					} else {
						array.add(fromJDBC(element));
					}
				}
				arrayEntity.set(dynamicArrayType.getPropertiesNames().get(0), array);
				entity.set(propertyName, arrayEntity);
			} else if (attribute != null) {
				Object value = fromJDBC(attribute);
				if (dynamicType.getPropertyType(propertyName) != null) {
					entity.set(propertyName, value);
				} else {
					// For nillable elements. Refer to: https://stackoverflow.com/questions/20396375/why-and-when-jaxbelement-is-required-in-jaxb
					XMLDescriptor descriptor = (XMLDescriptor) dynamicType.getDescriptor();
					XMLField xmlField = (XMLField) descriptor.getFields().get(i);
					if (value instanceof Node) {
						// Assume that there is an element into which the any is wrapped 
						XMLChoiceObjectMapping mapping = (XMLChoiceObjectMapping) descriptor.getMappingForAttributeName(propertyName);
						Class<?> inner = mapping.getFieldToClassMappings().get(xmlField);
						DynamicType dynamicTypeInner = _jaxbContext.getDynamicType(inner.getName());
						String propName = dynamicTypeInner.getPropertiesNames().get(0);
						DynamicEntity entityInner = dynamicTypeInner.newDynamicEntity();
						value = entityInner.set(propName, value);
					}
					entity.set(propertyName, createJAXBElement(namespace, xmlField.getXPathFragment().getXPath(), value));
				}
			}
		}
		return entity;
	}

	public static Object fromJDBC(Object value) throws SQLException {
		if (value instanceof Date) {
			GregorianCalendar calendar = new GregorianCalendar(JDBCParameter.TIME_ZONE);
			calendar.setTime((Date) value);
			return datatypeFactory.newXMLGregorianCalendar(calendar);
		}
		if (value instanceof SQLXML) {
			DOMSource domSource = ((SQLXML) value).getSource(DOMSource.class);
			return domSource.getNode();
		}
		if (value instanceof Blob) {
			Blob blob = (Blob) value;
			return blob.getBytes(1, (int) blob.length());
		}
		return value;
	}

	public JAXBElement<?> createAttachments(ESBMessage message, String namespace, String elementName) throws Exception {
		DynamicEntity entity = createDynamicEntity(namespace, elementName, false);
		DynamicType dynamicType = DynamicHelper.getType(entity);
		XMLDescriptor descriptor = (XMLDescriptor) dynamicType.getDescriptor();
		XMLCompositeObjectMapping mapping = (XMLCompositeObjectMapping) descriptor.getMappingForAttributeName("attachments");
		DynamicType dynamicTypeInner = _jaxbContext.getDynamicType(mapping.getReferenceClassName());
		XMLDescriptor descriptorInner = (XMLDescriptor) dynamicTypeInner.getDescriptor();
		DynamicEntity attachments = dynamicTypeInner.newDynamicEntity();
		entity.set("attachments", attachments);
		List<String> propertiesNames = dynamicTypeInner.getPropertiesNames();
		if (propertiesNames.size() == 1) {
			DatabaseMapping mappingInner = descriptorInner.getMappingForAttributeName(propertiesNames.get(0));
			if (mappingInner.isAbstractCompositeCollectionMapping()) {
				XMLCompositeCollectionMapping mappingAttachments = (XMLCompositeCollectionMapping) mappingInner;
				DynamicType dynamicTypeAttachment = _jaxbContext.getDynamicType(mappingAttachments.getReferenceClassName());
				List<DynamicEntity> array = new ArrayList<>();
				for (Iterator<String> iter = message.getAttachments().keySet().iterator(); iter.hasNext();) {
					String cid = iter.next();
					MimeBodyPart mimeBodyPart = message.getAttachments().get(cid);
					DynamicEntity attachment = dynamicTypeAttachment.newDynamicEntity();
					for (String propertyName : dynamicTypeAttachment.getPropertiesNames()) {
						switch (propertyName) {
						case "contentId":
							attachment.set(propertyName, cid);
							break;
						case "contentType":
							attachment.set(propertyName, mimeBodyPart.getContentType());
							break;
						default:
							attachment.set(propertyName, StreamUtils.copy(mimeBodyPart.getInputStream()));
							break;
						}
					}
					array.add(attachment);
					iter.remove();
				}
				attachments.set(propertiesNames.get(0), array);
				return createJAXBElement(namespace, elementName, entity);
			}
		}
		throw new IllegalArgumentException("Cannot map attachments to " + elementName + " in " + namespace);
	}

	public static void parseAttachments(Object object, ESBMessage message) throws Exception {
		@SuppressWarnings("unchecked")
		JAXBElement<DynamicEntity> jaxbElement = (JAXBElement<DynamicEntity>) object;
		DynamicEntity entity = jaxbElement.getValue();
		DynamicEntity attachments = entity.get("attachments");
		List<DynamicEntity> list = attachments.get("attachment");
		for (DynamicEntity attachment : list) {
			String cid = null;
			String contentType = null;
			byte[] content = null;
			DynamicType dynamicType = DynamicHelper.getType(attachment);
			for (String propertyName : dynamicType.getPropertiesNames()) {
				switch (propertyName) {
				case "contentId":
					cid = attachment.get(propertyName);
					break;
				case "contentType":
					contentType = attachment.get(propertyName);
					break;
				default:
					content = attachment.get(propertyName);
					break;
				}
			}
			message.addAttachment(cid, contentType, content, null);
		}
	}

}
