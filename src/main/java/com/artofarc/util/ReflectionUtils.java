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

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.StringTokenizer;

public final class ReflectionUtils {

	@SuppressWarnings("unchecked")
	public static <T> T eval(Object root, String path, Object... params) throws ReflectiveOperationException {
		StringTokenizer tokenizer1 = new StringTokenizer(path, ".");
		while (tokenizer1.hasMoreTokens()) {
			String part = tokenizer1.nextToken();
			StringTokenizer tokenizer2 = new StringTokenizer(part, "(,)");
			String methodName = tokenizer2.nextToken();
			List<Object> args = new ArrayList<>();
			while (tokenizer2.hasMoreTokens()) {
				String argName = tokenizer2.nextToken();
				int argPos = Integer.parseInt(argName.substring(1)) - 1;
				args.add(params[argPos]);
			}
			Method method = findMethod(root.getClass().getDeclaredMethods(), methodName, args);
			if (method == null) {
				method = findMethod(root.getClass().getMethods(), methodName, args);
				if (method == null) {
					throw new NoSuchMethodException(methodName);
				}
			}
			method.setAccessible(true);
			root = method.invoke(root, args.toArray());
		}
		return (T) root;
	}

	private static Method findMethod(Method[] methods, String methodName, List<Object> args) throws NoSuchMethodException {
		for (Method method : methods) {
			Class<?>[] parameterTypes = method.getParameterTypes();
			if (parameterTypes.length == args.size() && isMatch(method.getName(), methodName, args.size())) {
				for (int i = 0; i < parameterTypes.length; ++i) {
					if (!parameterTypes[0].isInstance(args.get(i))) {
						continue;
					}
				}
				return method;
			}
		}
		return null;
	}

	private static boolean isMatch(String name, String methodName, int argssize) {
		switch (argssize) {
		case 0:
			if (isMatch("get", name, methodName) || isMatch("is", name, methodName)) {
				return true;
			}
			break;
		case 1:
			if (isMatch("set", name, methodName)) {
				return true;
			}
			break;
		}
		return name.equals(methodName);
	}

	private static boolean isMatch(String prefix, String name, String methodName) {
		return name.startsWith(prefix) && name.charAt(prefix.length()) == Character.toUpperCase(methodName.charAt(0))
				&& name.substring(prefix.length() + 1).equals(methodName.substring(1));
	}

	public static Object newInstanceInnerStatic(Object enclosing, String name, Object... params) throws ReflectiveOperationException {
		return newInstance(enclosing.getClass().getName() + "$" + name, params);
	}

	public static Object newInstancePackage(Object enclosing, String name, Object... params) throws ReflectiveOperationException {
		return newInstance(enclosing.getClass().getPackage().getName() + "." + name, params);
	}

	public static Object newInstance(String className, Object... params) throws ReflectiveOperationException {
		Class<?> cls = Class.forName(className);
		Constructor<?> con = findConstructor(cls, Arrays.asList(params));
		con.setAccessible(true);
		return con.newInstance(params);
	}

	@SuppressWarnings("unchecked")
	private static <T> Constructor<T> findConstructor(Class<T> cls, List<Object> params) throws NoSuchMethodException {
		for (Constructor<?> con : cls.getConstructors()) {
			Class<?>[] parameterTypes = con.getParameterTypes();
			if (parameterTypes.length == params.size()) {
				for (int i = 0; i < parameterTypes.length; ++i) {
					if (!parameterTypes[0].isInstance(params.get(0))) {
						continue;
					}
				}
				return (Constructor<T>) con;
			}
		}
		throw new NoSuchMethodException();
	}

	public static Object[] toArray(List<Object> list) {
		Object[] array = (Object[]) Array.newInstance(list.get(0).getClass(), list.size());
		return list.toArray(array);
	}

	public static <T extends Throwable> T convert(Throwable t, Class<T> cls, Object... params) {
		T result;
		if (cls.isInstance(t)) {
			result = cls.cast(t);
		} else {
			Throwable cause = t.getCause();
			if (cls.isInstance(cause)) {
				result = cls.cast(cause);
			} else {
				try {
					List<Object> list = new ArrayList<>(Arrays.asList(params));
					list.add(t);
					Constructor<T> con = findConstructor(cls, list);
					result = con.newInstance(list.toArray());
				} catch (ReflectiveOperationException e) {
					throw new RuntimeException(e);
				}
			}
		}
		return result;
	}

}
