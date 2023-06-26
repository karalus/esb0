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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class ReflectionUtils {

	private final static Object[] EMPTY_OBJECT_ARRAY = new Object[0];

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
		return eval(root, path, 0, paramResolver);
	}

	public static int findNextDelim(CharSequence s, int i, CharSequence delim) {
		for (boolean quoted = false; i < s.length(); ++i) {
			final char c = s.charAt(i);
			if (c == '\'') {
				quoted = !quoted;
			} else if (!quoted) {
				for (int j = delim.length(); j > 0;) {
					if (c == delim.charAt(--j)) {
						return i;
					}
				}
			}
		}
		return -1;
	}

	@SuppressWarnings("unchecked")
	public static <T, E extends Exception> T eval(Object root, String path, int pos, ParamResolver<E> paramResolver) throws ReflectiveOperationException, E {
		outer: for (List<Object> args = new ArrayList<>(); pos < path.length(); args.clear()) {
			String methodName;
			int i = findNextDelim(path, pos, "(.");
			if (i < 0) {
				methodName = path.substring(pos);
				pos = path.length();
			} else {
				methodName = path.substring(pos, i);
				pos = i + 1;
				if (path.charAt(i) == '(') {
					do {
						i = findNextDelim(path, pos, ",)");
						if (i > pos) {
							String param = path.substring(pos, i);
							args.add(paramResolver.resolve(param));
							pos = i;
						} else if (i < 0) {
							throw new IllegalArgumentException("Missing ')' or ','");
						}
					} while (path.charAt(pos++) != ')');
					++pos;
				}
			}
			Method method = findMethod(root.getClass().getMethods(), methodName, args);
			if (method != null) {
				method.setAccessible(true);
				root = method.invoke(root, args.isEmpty() ? EMPTY_OBJECT_ARRAY : args.toArray());
			} else {
				for (Class<?> cls = root.getClass(); cls != null; cls = cls.getSuperclass()) {
					method = findMethod(cls.getDeclaredMethods(), methodName, args);
					if (method != null) {
						method.setAccessible(true);
						root = method.invoke(root, args.isEmpty() ? EMPTY_OBJECT_ARRAY : args.toArray());
						continue outer;
					}
				}
				throw new NoSuchMethodException(methodName + " for args " + args + " in " + root.getClass().getName());
			}
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

	private final static Map<Class<?>, Class<?>[]> primitiveMapper = DataStructures.createMap(
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

	public static Class<?> classForName(String name) throws ClassNotFoundException {
		for (Class<?> cls : primitiveMapper.keySet()) {
			if (cls.getName().equals(name)) {
				return cls;
			}
		}
		return Class.forName(name);
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
		throw new NoSuchMethodException(cls + " has no ctor for " + params);
	}

	public static Map.Entry<Class<?>, MethodHandle>[] findConstructors(Class<?> cls, int parameterCount) throws ReflectiveOperationException {
		List<Map.Entry<Class<?>, MethodHandle>> result = new ArrayList<>();
		for (Constructor<?> constructor : cls.getConstructors()) {
			if (constructor.getParameterCount() == parameterCount) {
				addToResult(result, DataStructures.createEntry(parameterCount > 0 ? constructor.getParameterTypes()[0] : null, MethodHandles.publicLookup().unreflectConstructor(constructor)));
			}
		}
		if (result.isEmpty()) {
			throw new NoSuchMethodException(cls + " has no ctor with parameterCount " + parameterCount);
		}
		return DataStructures.toArray(result);
	}

	public static Map.Entry<Class<?>, MethodHandle>[] findStaticMethods(Class<?> cls, String name, int parameterCount) throws ReflectiveOperationException {
		List<Map.Entry<Class<?>, MethodHandle>> result = new ArrayList<>();
		for (Method method : cls.getMethods()) {
			if (method.getParameterCount() == parameterCount && method.getName().equals(name) && (method.getModifiers() & Modifier.STATIC) != 0) {
				addToResult(result, DataStructures.createEntry(parameterCount > 0 ? method.getParameterTypes()[0] : null, MethodHandles.publicLookup().unreflect(method)));
			}
		}
		if (result.isEmpty()) {
			throw new NoSuchMethodException(cls + " has no static method " + name + " with parameterCount " + parameterCount);
		}
		return DataStructures.toArray(result);
	}

	private static void addToResult(List<Map.Entry<Class<?>, MethodHandle>> result, Map.Entry<Class<?>, MethodHandle> entry) {
		int i = 0;
		for (; i < result.size(); ++i) {
			if (result.get(i).getKey().isAssignableFrom(entry.getKey())) {
				break;
			}
		}
		result.add(i, entry);
	}

	public static Object invokePolymorphic(Map.Entry<Class<?>, MethodHandle>[] methodHandles, Object value) throws Throwable {
		for (Map.Entry<Class<?>, MethodHandle> entry : methodHandles) {
			if (!isNotAssignable(entry.getKey(), value)) {
				return entry.getValue().invoke(value);
			}
		}
		throw new IllegalArgumentException("No match found for " + value.getClass());
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

	private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
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

	public static MethodHandle unreflectGetter(Class<?> cls, String name) {
		try {
			Field field = findField(cls, name);
			field.setAccessible(true);
			return MethodHandles.lookup().unreflectGetter(field);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	public static <T> T invoke(MethodHandle methodHandle, Object obj) {
		try {
			return (T) methodHandle.invoke(obj);
		} catch (Throwable e) {
			throw ReflectionUtils.convert(e, RuntimeException.class);
		}
	}

}
