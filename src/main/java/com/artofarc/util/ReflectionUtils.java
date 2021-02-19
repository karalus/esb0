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

import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

public final class ReflectionUtils {

	public interface ParamResolver<E extends Exception> {
		Object resolve(String param) throws E;
	}

	public static <T> T eval(Object root, String path, final Object... params) throws ReflectiveOperationException {
		ParamResolver<RuntimeException> paramResolver = params.length == 0 ? null : new ParamResolver<RuntimeException>() {

			@Override
			public Object resolve(String argName) {
				int argPos = Integer.parseInt(argName.substring(1)) - 1;
				return params[argPos];
			}
		};
		return eval(root, path, paramResolver);
	}

	@SuppressWarnings("unchecked")
	public static <T, E extends Exception> T eval(Object root, String path, ParamResolver<E> paramResolver) throws ReflectiveOperationException, E {
		StringTokenizer tokenizerChain = new StringTokenizer(path, ".");
		while (tokenizerChain.hasMoreTokens()) {
			String part = tokenizerChain.nextToken();
			StringTokenizer tokenizerMethod = new StringTokenizer(part, "(,)");
			String methodName = tokenizerMethod.nextToken();
			List<Object> args = new ArrayList<>();
			while (tokenizerMethod.hasMoreTokens()) {
				String argName = tokenizerMethod.nextToken();
				args.add(paramResolver.resolve(argName));
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

	private static Method findMethod(Method[] methods, String methodName, List<Object> args) {
		outer: for (Method method : methods) {
			Class<?>[] parameterTypes = method.getParameterTypes();
			if (parameterTypes.length <= args.size() && isMatch(method.getName(), methodName, args.size())) {
				for (int i = 0; i < parameterTypes.length; ++i) {
					Class<?> parameterType = parameterTypes[i];
					boolean last = i == parameterTypes.length - 1;
					if (last && method.isVarArgs()) {
						parameterType = parameterType.getComponentType();
						int varArgsLength = args.size() - i;
						Object[] varArgs = (Object[]) Array.newInstance(parameterType, varArgsLength);
						for (int j = 0; j < varArgsLength; ++j) {
							Object arg = args.get(i + j);
							if (isNotAssignable(parameterType, arg)) {
								continue outer;
							}
							varArgs[j] = arg;
						}
						args.set(i++, varArgs);
						for (; i < args.size(); ++i) {
							args.remove(i);
						}
					} else if (isNotAssignable(parameterType, args.get(i))) {
						continue outer;
					}
				}
				if (args.size() == parameterTypes.length) {
					return method;
				}
			}
		}
		return null;
	}

	private final static Map<Class<?>, Class<?>[]> primitiveMapper = Collections.createMap(
			double.class, new Class[] { Double.class, Float.class, Long.class, Integer.class, Short.class, Byte.class },
			float.class, new Class[] { Float.class, Long.class, Integer.class, Short.class, Byte.class },
			long.class, new Class[] { Long.class, Integer.class, Short.class, Byte.class },
			int.class, new Class[] { Integer.class, Short.class, Byte.class },
			short.class, new Class[] { Short.class, Byte.class },
			byte.class, new Class[] { Byte.class },
			boolean.class, new Class[] { Boolean.class },
			char.class, new Class[] { Character.class });

	private static boolean isNotAssignable(Class<?> parameterType, Object arg) {
		if (parameterType.isPrimitive()) {
			if (arg == null) {
				return true;
			}
			for (Class<?> cls : primitiveMapper.get(parameterType)) {
				if (cls.isInstance(arg)) {
					return false;
				}
			}
			return true;
		}
		return arg != null && !parameterType.isInstance(arg);
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
		return prefix.length() + methodName.length() == name.length() && name.startsWith(prefix) && name.charAt(prefix.length()) == Character.toUpperCase(methodName.charAt(0))
				&& name.substring(prefix.length() + 1).equals(methodName.substring(1));
	}

	public static Object newInstanceInnerStatic(Object enclosing, String name, Object... params) throws ReflectiveOperationException {
		return newInstance(enclosing.getClass().getClassLoader(), enclosing.getClass().getName() + '$' + name, params);
	}

	public static Object newInstancePackage(Object enclosing, String name, Object... params) throws ReflectiveOperationException {
		return newInstance(enclosing.getClass().getClassLoader(), enclosing.getClass().getPackage().getName() + '.' + name, params);
	}

	public static Object newInstance(ClassLoader classLoader, String className, Object... params) throws ReflectiveOperationException {
		Class<?> cls = Class.forName(className, true, classLoader);
		Constructor<?> con = findConstructor(cls, Arrays.asList(params));
		con.setAccessible(true);
		return con.newInstance(params);
	}

	@SuppressWarnings("unchecked")
	private static <T> Constructor<T> findConstructor(Class<T> cls, List<Object> params) throws NoSuchMethodException {
		outer: for (Constructor<?> con : cls.getConstructors()) {
			Class<?>[] parameterTypes = con.getParameterTypes();
			if (parameterTypes.length == params.size()) {
				for (int i = 0; i < parameterTypes.length; ++i) {
					if (isNotAssignable(parameterTypes[i], params.get(i))) {
						continue outer;
					}
				}
				return (Constructor<T>) con;
			}
		}
		throw new NoSuchMethodException();
	}

	@SuppressWarnings("unchecked")
	public static <T> Constructor<T> findConstructor(String className, Class<?>... parameterTypes) {
		try {
			return (Constructor<T>) Class.forName(className).getDeclaredConstructor(parameterTypes);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	public static Constructor<?> findAnyConstructor(Class<?> cls, Class<?>... anyOfType) throws NoSuchMethodException {
		for (Class<?> parameterType : anyOfType) {
			try {
				return cls.getConstructor(parameterType);
			} catch (NoSuchMethodException e) {
				// continue
			}
		}
		throw new NoSuchMethodException(cls + " has no ctor for any of " + Arrays.asList(anyOfType));
	}

	public static Method findAnyMethod(Class<?> cls, String name, Class<?>... anyOfType) throws NoSuchMethodException {
		for (Class<?> parameterType : anyOfType) {
			try {
				return cls.getMethod(name, parameterType);
			} catch (NoSuchMethodException e) {
				// continue
			}
		}
		for (Method method : cls.getMethods()) {
			if (method.getParameterCount() == 1 && method.getName().equals(name)) {
				return method;
			}
		}
		throw new NoSuchMethodException(cls + " has no method " + name + " with any parameter type of " + Arrays.asList(anyOfType));
	}

	@SuppressWarnings("unchecked")
	public static <T> T getField(Object obj, String name) {
		try {
			Field field = findField(obj.getClass(), name);
			field.setAccessible(true);
			return (T) field.get(obj);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	public static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
		try {
			return cls.getDeclaredField(name);
		} catch (NoSuchFieldException e) {
			if (cls.getSuperclass() != null) {
				return findField(cls.getSuperclass(), name);
			}
			throw e;
		}
	}

	public static void checkStatic(Member member) {
		if ((member.getModifiers() & Modifier.STATIC) == 0) {
			throw new IllegalArgumentException("Member must be static: " + member);
		}
	}

	public static <E extends Exception> E convert(Throwable t, Class<E> cls, Object... params) {
		if (t instanceof Error) {
			throw (Error) t;
		}
		E result;
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
					Constructor<E> con = findConstructor(cls, list);
					result = con.newInstance(list.toArray());
				} catch (ReflectiveOperationException e) {
					throw convert(t, RuntimeException.class);
				}
			}
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	public static <T, E extends Exception> T invoke(Method method, Class<E> cls, Object obj, Object... params) throws E {
		try {
			return (T) method.invoke(obj, params);
		} catch (InvocationTargetException e) {
			throw ReflectionUtils.convert(e.getCause(), cls);
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

}
