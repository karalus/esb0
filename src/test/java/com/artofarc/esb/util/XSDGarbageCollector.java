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
package com.artofarc.esb.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.HashMap;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;

import com.artofarc.util.IOUtils.PreventFlushOutputStream;
import com.sun.xml.xsom.*;
import com.sun.xml.xsom.impl.SchemaImpl;
import com.sun.xml.xsom.impl.SchemaSetImpl;
import com.sun.xml.xsom.visitor.XSVisitor;

public final class XSDGarbageCollector implements XSVisitor {

	private final SchemaSetImpl srcSchemas = new SchemaSetImpl(), destSchemas = new SchemaSetImpl();
	private final ArrayDeque<SchemaImpl> stack = new ArrayDeque<>();
	private final Map<SchemaImpl, Set<String>> refNs = new HashMap<>();

	public void addSourceSchemaSet(XSSchemaSet schemaSet) {
		for (XSSchema schema : schemaSet.getSchemas()) {
			SchemaImpl schema1 = srcSchemas.createSchema(schema.getTargetNamespace(), schema.getLocator());
			for (XSElementDecl elementDecl : schema.getElementDecls().values()) {
				schema1.addElementDecl(elementDecl);
			}
			for (XSComplexType complexType : schema.getComplexTypes().values()) {
				schema1.addComplexType(complexType, true);
			}
			for (XSSimpleType simpleType : schema.getSimpleTypes().values()) {
				schema1.addSimpleType(simpleType, true);
			}
			for (XSModelGroupDecl modelGroupDecl : schema.getModelGroupDecls().values()) {
				schema1.addModelGroupDecl(modelGroupDecl, true);
			}
			for (XSAttGroupDecl attGroupDecl : schema.getAttGroupDecls().values()) {
				schema1.addAttGroupDecl(attGroupDecl, true);
			}
			for (XSAttributeDecl attributeDecl : schema1.getAttributeDecls().values()) {
				schema1.addAttributeDecl(attributeDecl);
			}
		}
	}

	public void addElement(String elementQName) {
		QName element = QName.valueOf(elementQName);
		XSElementDecl elementDecl = srcSchemas.getElementDecl(element.getNamespaceURI(), element.getLocalPart());
		if (elementDecl == null) {
			throw new IllegalArgumentException("Not found: " + elementQName);
		}
		addDeclaration(elementDecl);
	}

	public void addDeclaration(XSDeclaration declaration) {
		if (declaration != null && declaration.isGlobal() && !declaration.getTargetNamespace().equals(XMLConstants.W3C_XML_SCHEMA_NS_URI)) {
			SchemaImpl schema = destSchemas.createSchema(declaration.getTargetNamespace(), declaration.getOwnerSchema().getLocator());
			SchemaImpl currentSchema = stack.peek();
			boolean pushed = currentSchema != schema;
			if (pushed) {
				if (currentSchema != null) {
					// add dependency
					Set<String> refs = refNs.get(currentSchema);
					if (refs == null) {
						refNs.put(currentSchema, refs = new LinkedHashSet<>());
					}
					refs.add(declaration.getTargetNamespace());
				}
				stack.push(schema);
			}
			// Type switch only possible from JDK 17 on
			if (declaration instanceof XSElementDecl) {
				XSElementDecl elementDecl = schema.getElementDecl(declaration.getName());
				if (elementDecl == null) {
					schema.addElementDecl((XSElementDecl) declaration);
					declaration.visit(this);
				}
			} else if (declaration instanceof XSSimpleType) {
				XSSimpleType simpleType = schema.getSimpleType(declaration.getName());
				if (simpleType == null) {
					schema.addSimpleType((XSSimpleType) declaration, true);
					declaration.visit(this);
				}
			} else if (declaration instanceof XSComplexType) {
				XSComplexType complexType = schema.getComplexType(declaration.getName());
				if (complexType == null) {
					schema.addComplexType((XSComplexType) declaration, true);
					declaration.visit(this);
				}
			} else if (declaration instanceof XSModelGroupDecl) {
				XSModelGroupDecl modelGroupDecl = schema.getModelGroupDecl(declaration.getName());
				if (modelGroupDecl == null) {
					schema.addModelGroupDecl((XSModelGroupDecl) declaration, true);
					declaration.visit(this);
				}
			} else if (declaration instanceof XSAttGroupDecl) {
				XSAttGroupDecl attGroupDecl = schema.getAttGroupDecl(declaration.getName());
				if (attGroupDecl == null) {
					schema.addAttGroupDecl((XSAttGroupDecl) declaration, true);
					declaration.visit(this);
				}
			} else if (declaration instanceof XSAttributeDecl) {
				XSAttributeDecl attributeDecl = schema.getAttributeDecl(declaration.getName());
				if (attributeDecl == null) {
					schema.addAttributeDecl((XSAttributeDecl) declaration);
					declaration.visit(this);
				}
			}
			if (pushed) {
				stack.pop();
			}
		}
	}

	private void writeSchema(XSSchema schema, Function<String, String> uri2filename, Writer writer) {
		Map<String, String> namespaceMap = new HashMap<>();
		namespaceMap.put(schema.getTargetNamespace(), "tns");
		Set<String> hashSet = refNs.get(schema);
		if (hashSet != null) {
			int i = 1;
			for (String ns : hashSet) {
				namespaceMap.put(ns, "ns" + i++);
			}
		}
		SchemaWriter schemaWriter = new SchemaWriter(writer);
		schemaWriter.setUri2filenameFunction(uri2filename);
		schemaWriter.setNamespaceMap(namespaceMap);
		schemaWriter.schema(schema);
	}

	public void dump() throws IOException {
		Writer writer = new OutputStreamWriter(System.out);
		for (XSSchema schema : destSchemas.getSchemas()) {
			writeSchema(schema, null, writer);
			System.out.println();
		}
	}

	public void dump(File dir) throws IOException {
		dump(dir, ns -> UriHelper.convertUri(ns, "-", false), StandardCharsets.UTF_8);
	}

	public void dump(File dir, Function<String, String> uri2filename, Charset charset) throws IOException {
		for (XSSchema schema : destSchemas.getSchemas()) {
			String filename = uri2filename.apply(schema.getTargetNamespace());
			if (filename != null) {
				// SchemaWriter flushes too often
				try (OutputStreamWriter writer = new OutputStreamWriter(new PreventFlushOutputStream(new FileOutputStream(new File(dir, filename + ".xsd"))), charset)) {
					writeSchema(schema, uri2filename, writer);
				}
				XSSchema schema1 = srcSchemas.getSchema(schema.getTargetNamespace());
				System.out.println("Statistics for " + schema.getTargetNamespace());
				System.out.println("Discarded elements" + diffKeys(schema1.getElementDecls(), schema.getElementDecls()));
				System.out.println("Discarded complexTypes" + diffKeys(schema1.getComplexTypes(), schema.getComplexTypes()));
				System.out.println("Discarded simpleTypes" + diffKeys(schema1.getSimpleTypes(), schema.getSimpleTypes()));
			}
		}
	}

	private static String diffKeys(Map<String, ?> map1, Map<String, ?> map2) {
		int size = map1.size();
		if (size > 0) {
			Set<String> result = new LinkedHashSet<>(map1.keySet());
			result.removeAll(map2.keySet());
			return ", " + result.size() + " out of " + size + ": " + result;
		}
		return ": N/A";
	}

	@Override
	public void elementDecl(XSElementDecl decl) {
		addDeclaration(decl);
		decl.getType().visit(this);
		for (XSElementDecl elementDecl : decl.getSubstitutables()) {
			addDeclaration(elementDecl);
		}
	}

	@Override
	public void complexType(XSComplexType type) {
		addDeclaration(type);
		addDeclaration(type.getBaseType());
		XSContentType contentType = type.getContentType();
		XSParticle particle = contentType.asParticle();
		if (particle != null) {
			particle.getTerm().visit(this);
		} else if (contentType.asSimpleType() != null) {
			contentType.asSimpleType().visit(this);
		}
		for (XSAttributeUse attributeUse : type.getDeclaredAttributeUses()) {
			attributeUse.getDecl().getType().visit(this);
		}
		for (XSAttGroupDecl xsAttGroupDecl : type.getAttGroups()) {
			addDeclaration(xsAttGroupDecl);
		}
		for (XSComplexType subType : type.getSubtypes()) {
			addDeclaration(subType);
		}
	}

	@Override
	public void simpleType(XSSimpleType simpleType) {
		addDeclaration(simpleType);
		addDeclaration(simpleType.getSimpleBaseType());
		if (simpleType.isList()) {
			addDeclaration(simpleType.asList().getItemType());
		} else if (simpleType.isUnion()) {
			XSUnionSimpleType unionSimpleType = simpleType.asUnion();
			for (int i = unionSimpleType.getMemberSize(); i > 0;) {
				addDeclaration(unionSimpleType.getMember(--i));
			}
		}
	}

	@Override
	public void modelGroup(XSModelGroup group) {
		for (XSParticle xsParticle : group.getChildren()) {
			xsParticle.getTerm().visit(this);
		}
	}

	@Override
	public void modelGroupDecl(XSModelGroupDecl decl) {
		addDeclaration(decl);
		decl.getModelGroup().visit(this);
	}

	@Override
	public void attributeDecl(XSAttributeDecl decl) {
		addDeclaration(decl);
		addDeclaration(decl.getType());
	}

	@Override
	public void attGroupDecl(XSAttGroupDecl decl) {
		addDeclaration(decl);
		for (XSAttGroupDecl attGroupDecl : decl.getAttGroups()) {
			addDeclaration(attGroupDecl);
		}
	}

	@Override
	public void wildcard(XSWildcard wc) {
		System.err.println("Warning XSWildcard found, reduced schema might be incomplete");
	}

	@Override
	public void particle(XSParticle particle) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void empty(XSContentType empty) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void annotation(XSAnnotation ann) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void attributeUse(XSAttributeUse use) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void schema(XSSchema schema) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void facet(XSFacet facet) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void notation(XSNotation notation) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void identityConstraint(XSIdentityConstraint decl) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void xpath(XSXPath xp) {
		throw new UnsupportedOperationException();
	}

}
