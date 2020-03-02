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

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map.Entry;

/**
 * This B+Tree allows for lookup of a prefix for a key.
 */
public final class BPlusTree<T> {

	private static boolean startsWith(char[] s, char[] p, int o, int l) {
		if (l > s.length)
			return false;
		for (; o < l; ++o)
			if (s[o] != p[o])
				return false;
		return true;
	}

	private final static class Node<T> {
		private final List<Entry<char[], ?>> _list;

		Node(List<Entry<char[], ?>> list) {
			_list = list;
		}

		private void addByLength(Entry<char[], ?> entry) {
			for (int i = 0; i < _list.size(); ++i) {
				Entry<char[], ?> pivot = _list.get(i);
				if (entry.getKey().length > pivot.getKey().length) {
					_list.add(i, entry);
					return;
				}
			}
			_list.add(entry);
		}

		void insert(char[] key, T value) {
			List<Entry<char[], ?>> common = new ArrayList<>();
			for (int l = key.length; l > 0; --l) {
				for (ListIterator<Entry<char[], ?>> iter = _list.listIterator(); iter.hasNext();) {
					Entry<char[], ?> entry = iter.next();
					if (startsWith(entry.getKey(), key, 0, l)) {
						if (entry.getKey().length == l && entry.getValue() instanceof Node) {
							// equal and there is already a sub tree for it
							@SuppressWarnings("unchecked")
							Node<T> inner = (Node<T>) entry.getValue();
							inner.insert(key, value);
							return;
						} else {
							iter.remove();
							common.add(entry);
						}
					}
				}
				if (common.size() > 0) {
					if (_list.isEmpty()) {
						// rollback, this prefix will not split
						_list.addAll(common);
						break;
					} else {
						// split
						Node<T> node = new Node<>(common);
						node.addByLength(Collections.createEntry(key, value));
						char[] prefix = new char[l];
						System.arraycopy(key, 0, prefix, 0, l);
						addByLength(Collections.createEntry(prefix, node));
						return;
					}
				}
			}
			addByLength(Collections.createEntry(key, value));
		}

		@SuppressWarnings("unchecked")
		T remove(char[] key, int offset) {
			for (ListIterator<Entry<char[], ?>> iter = _list.listIterator(); iter.hasNext();) {
				Entry<char[], ?> entry = iter.next();
				int keyLen = entry.getKey().length;
				if (startsWith(key, entry.getKey(), offset, keyLen)) {
					if (entry.getValue() instanceof Node) {
						Node<T> inner = (Node<T>) entry.getValue();
						T result = inner.remove(key, keyLen);
						if (inner._list.size() == 1) {
							// Condense
							iter.set(inner._list.get(0));
						}
						return result;
					} else if (keyLen == key.length) {
						iter.remove();
						return (T) entry.getValue();
					}
				}
			}
			return null;
		}

		@SuppressWarnings("unchecked")
		T search(char[] path, int offset, int minKeyLen) {
			for (Entry<char[], ?> entry : _list) {
				int keyLen = entry.getKey().length;
				if (startsWith(path, entry.getKey(), offset, keyLen)) {
					if (entry.getValue() instanceof Node) {
						Node<T> inner = (Node<T>) entry.getValue();
						return inner.search(path, keyLen, minKeyLen);
					} else if (keyLen >= minKeyLen) {
						return (T) entry.getValue();
					}
				}
			}
			return null;
		}

		@SuppressWarnings("unchecked")
		void collectLeaves(List<Entry<char[], T>> leaves) {
			for (Entry<char[], ?> entry : _list) {
				if (entry.getValue() instanceof Node) {
					Node<T> inner = (Node<T>) entry.getValue();
					inner.collectLeaves(leaves);
				} else {
					leaves.add((Entry<char[], T>) entry);
				}
			}
		}

	}

	private final Node<T> _root = new Node<>(new ArrayList<Entry<char[], ?>>());

	public synchronized void insert(String key, T value) {
		_root.insert(key.toCharArray(), value);
	}

	public synchronized T remove(String key) {
		return _root.remove(key.toCharArray(), 0);
	}

	public synchronized T get(String key) {
		return _root.search(key.toCharArray(), 0, key.length());
	}

	public synchronized T search(String path) {
		return _root.search(path.toCharArray(), 0, 0);
	}

	private synchronized List<Entry<char[], T>> getLeaves() {
		List<Entry<char[], T>> leaves = new ArrayList<>();
		_root.collectLeaves(leaves);
		return leaves;
	}

	public List<String> getKeys() {
		List<String> result = new ArrayList<>();
		for (Entry<char[], T> entry : getLeaves()) {
			result.add(new String(entry.getKey()));
		}
		return result;
	}

	public List<T> getValues() {
		List<T> result = new ArrayList<>();
		for (Entry<char[], T> entry : getLeaves()) {
			result.add(entry.getValue());
		}
		return result;
	}

}
