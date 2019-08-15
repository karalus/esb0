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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Struct;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;

import javax.xml.bind.JAXBElement;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.namespace.QName;

import org.eclipse.persistence.dynamic.DynamicEntity;
import org.eclipse.persistence.dynamic.DynamicHelper;
import org.eclipse.persistence.dynamic.DynamicType;
import org.eclipse.persistence.jaxb.dynamic.DynamicJAXBContext;
import org.eclipse.persistence.oxm.XMLDescriptor;
import org.eclipse.persistence.oxm.XMLField;
import org.eclipse.persistence.oxm.mappings.XMLChoiceObjectMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.artofarc.util.ReflectionUtils;

public final class JDBCXMLMapper {

	private final static Logger logger = LoggerFactory.getLogger(JDBCXMLMapper.class);

	private final static DatatypeFactory datatypeFactory;
	private static Class<?> ifcOracleConnection;
	private static Method createARRAY;
	private static Method getSQLTypeName;
			
	static {
		try {
			datatypeFactory = DatatypeFactory.newInstance();
		} catch (javax.xml.datatype.DatatypeConfigurationException e) {
			throw new RuntimeException(e);
		}
		try {
			ifcOracleConnection = Class.forName("oracle.jdbc.OracleConnection");
			createARRAY = ifcOracleConnection.getMethod("createARRAY", String.class, Object.class);
			getSQLTypeName = Class.forName("oracle.sql.ARRAY").getMethod("getSQLTypeName");
		} catch (ReflectiveOperationException e) {
			// https://docs.oracle.com/cd/B28359_01/java.111/b31224/oraarr.htm#i1059642
			logger.warn("Oracle JDBC driver not in classpath. Mapping of Arrays will not work");
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

	public static Object toJDBC(DynamicEntity entity, boolean root, Connection connection) throws SQLException {
		DynamicType dynamicType = DynamicHelper.getType(entity);
		XMLDescriptor descriptor = (XMLDescriptor) dynamicType.getDescriptor();
   	QName typeName = descriptor.getSchemaReference().getSchemaContextAsQName();
		List<String> propertiesNames = dynamicType.getPropertiesNames();
		Object[] attributes = new Object[propertiesNames.size()];
		for (int i = 0; i < propertiesNames.size(); ++i) {
			Object property = entity.get(propertiesNames.get(i));
			if (property instanceof JAXBElement<?>) {
				attributes[i] = toJDBC((JAXBElement<?>) property, false, connection);
			} else if (property instanceof List<?>) {
				List<?> array = (List<?>) property;
				Object[] elements = new Object[array.size()];
				for (int j = 0; j < array.size(); ++j) {
					Object value = array.get(j);
					if (value instanceof DynamicEntity) {
						elements[j] = toJDBC((DynamicEntity) value, false, connection);
					} else {
						elements[j] = toJDBC(value);
					}
				}
				return createArray(connection, typeName.getLocalPart(), elements);
			} else if (property != null) {
				attributes[i] = toJDBC(property);
			}
		}
		return root ? attributes[0] : connection.createStruct(typeName.getLocalPart(), attributes);
	}

	public static Object toJDBC(JAXBElement<?> jaxbElement, boolean root, Connection connection) throws SQLException {
		Object value = jaxbElement.getValue();
		if (value instanceof DynamicEntity) {
			return toJDBC((DynamicEntity) value, root, connection);
		} else {
			return toJDBC(value);
		}
	}

	public static Object toJDBC(Object value) {
		if (value instanceof XMLGregorianCalendar) {
			XMLGregorianCalendar calendar = (XMLGregorianCalendar) value;
			return new Timestamp(calendar.toGregorianCalendar(JDBCResult2JsonMapper.TIME_ZONE, null, null).getTimeInMillis());
		}
		return value;
	}

	public String getTypeName(String namespace, String elementName) {
		DynamicEntity entity = (DynamicEntity) _jaxbContext.createByQualifiedName(namespace, elementName, false);
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
		DynamicEntity entity = (DynamicEntity) _jaxbContext.createByQualifiedName(namespace, elementName, false);
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
//		logger.info("Struct for " + struct.getSQLTypeName());
		DynamicEntity entity = (DynamicEntity) _jaxbContext.createByQualifiedName(namespace, struct.getSQLTypeName(), true);
		DynamicType dynamicType = DynamicHelper.getType(entity);
		List<String> propertiesNames = dynamicType.getPropertiesNames();
		Object[] attributes = struct.getAttributes();
		if (attributes.length != propertiesNames.size()) {
			throw new IllegalArgumentException(struct.getSQLTypeName() + " does not match " + dynamicType.getName());
		}
		for (int i = 0; i < attributes.length; ++i) {
			Object value = attributes[i];
			String propertyName = propertiesNames.get(i);
//			logger.info("Property: " + propertyName + " = " + value);
			if (value instanceof Struct) {
				Struct inner = (Struct) value;
				entity.set(propertyName, createJAXBElement(namespace, inner.getSQLTypeName(), fromJDBC(inner, namespace)));
			} else if (value instanceof Array) {
				Array inner = (Array) value;
//				logger.info("Array: " + getSQLTypeName(inner));
				DynamicEntity arrayEntity = (DynamicEntity) _jaxbContext.createByQualifiedName(namespace, getSQLTypeName(inner), true);
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
			} else if (value != null) {
				if (dynamicType.getPropertyType(propertyName) != null) {
					entity.set(propertyName, fromJDBC(value));
				} else {
					// For nillable elements. Refer to: https://stackoverflow.com/questions/20396375/why-and-when-jaxbelement-is-required-in-jaxb
					XMLDescriptor descriptor = (XMLDescriptor) dynamicType.getDescriptor();
					XMLField databaseField = (XMLField) descriptor.getFields().get(i);
					entity.set(propertyName, createJAXBElement(namespace, databaseField.getXPathFragment().getXPath(), fromJDBC(value)));
				}
			}
		}
		return entity;
	}

	public static Object fromJDBC(Object value) {
		if (value instanceof Date) {
			GregorianCalendar calendar = new GregorianCalendar(JDBCResult2JsonMapper.TIME_ZONE);
			calendar.setTime((Date) value);
			return datatypeFactory.newXMLGregorianCalendar(calendar);
		}
		return value;
	}

	public static Array createArray(Connection connection, String typeName, Object[] elements) throws SQLException {
		try {
			return (Array) createARRAY.invoke(connection.unwrap(ifcOracleConnection), typeName, elements);
		} catch (InvocationTargetException | IllegalAccessException e) {
			throw ReflectionUtils.convert(e.getCause(), SQLException.class);
		}
	}

	public static String getSQLTypeName(Array array) throws SQLException {
		try {
			return (String) getSQLTypeName.invoke(array);
		} catch (InvocationTargetException | IllegalAccessException e) {
			throw ReflectionUtils.convert(e.getCause(), SQLException.class);
		}
	}

}
