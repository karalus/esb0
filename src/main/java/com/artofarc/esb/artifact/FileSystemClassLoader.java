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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureClassLoader;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.artofarc.esb.message.ESBMessage;

public class FileSystemClassLoader extends SecureClassLoader {

	private final List<JarArtifact> _jars;
	
   public FileSystemClassLoader(List<JarArtifact> jars, ClassLoader parent) {
   	super(parent);
      _jars = jars;
   }

   /**
    * Order of classloading:
    * <ol>
    * <li>look in cache</li>
    * <li>call findClass: look in jars</li>
    * <li>propagate to parent classloader</li>
    * </ol>
    * @see FileSystemClassLoader#findClass(String)
    * @see ClassLoader#loadClass(String)
    */
   @Override
   public synchronized Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
      // First, check if the class has already been loaded
      Class<?> result = findLoadedClass(name);
      if (result == null) {
         try {
            result = findClass(name); // invoke our own find algorithm
         } catch (Exception e) { // also catches java.lang.SecurityException
            result = getParent().loadClass(name); // invoke parent
         }
      }
      if (resolve) {
         resolveClass(result);
      }
      return result;
   }


   @Override
   protected Class<?> findClass(final String name) throws ClassNotFoundException {
      String classFilename = name.replace('.', '/') + ".class";
      try {
         // search only with our own mechanism here!
      	byte[] classData = findInJarArtifacts(classFilename);
         if (classData != null) {
         	return defineClass(name, classData, 0, classData.length);
         }
      }
      catch (Exception e) {
         throw new ClassNotFoundException(name, e);
      }
      throw new ClassNotFoundException(name);
   }

   private byte[] findInJarArtifacts(final String filename) throws IOException {
   	// This is rather ineffective
   	for (JarArtifact jarArtifact : _jars) {
         try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(jarArtifact.getContent()))) {
   			ZipEntry entry;
   			while ((entry = zis.getNextEntry()) != null) {
   				if (!entry.isDirectory()) {
   					if (entry.getName().equals(filename)) {
   						ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
   						ESBMessage.copyStream(zis, bos);
   						return bos.toByteArray();		
   					}
   				}
   			}
         }
   	}
      return null;
   }

	@Override
	public InputStream getResourceAsStream(String name) {
		try {
			byte[] data = findInJarArtifacts(name);
			if (data != null) {
				return new ByteArrayInputStream(data);
			}
		} catch (IOException e) {
			return null;
		}
		return super.getResourceAsStream(name);
	}

}
