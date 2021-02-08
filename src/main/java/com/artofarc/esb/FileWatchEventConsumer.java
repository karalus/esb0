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
package com.artofarc.esb;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.GlobalContext;
import com.artofarc.esb.message.*;
import com.artofarc.util.IOUtils;

public final class FileWatchEventConsumer extends PollingConsumerPort {

	private final static long initialDelay = Long.parseLong(System.getProperty("esb0.fileWatch.initialDelay", "50"));

	private final List<Path> _dirs = new ArrayList<>();
	private final String _move, _moveOnError;
	private final StandardOpenOption[] _options;

	public FileWatchEventConsumer(GlobalContext globalContext, String uri, String workerPool, long pollInterval, List<String> dirs, String move, String moveOnError) throws Exception {
		super(uri, workerPool, pollInterval);
		for (String dir : dirs) {
			Path path = Paths.get(dir = globalContext.bindProperties(dir));
			if (!Files.isDirectory(path)) {
				throw new IOException("is not an existing directory " + dir);
			}
			_dirs.add(path);
		}
		_move = move;
		_moveOnError = moveOnError;
		if (move != null || moveOnError != null) {
			_options = new StandardOpenOption[] { StandardOpenOption.READ, StandardOpenOption.WRITE };
		} else {
			_options = new StandardOpenOption[] { StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE };
		}
	}

	public List<Path> getDirs() {
		return _dirs;
	}

	@Override
	public void run() {
		try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
			for (Path dir : _dirs) {
				dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
			}
			for (;;) {
				WatchKey watchKey = watchService.poll(_pollInterval, TimeUnit.MILLISECONDS);
				if (watchKey != null) {
					// File ready?
					Thread.sleep(initialDelay);
					for (WatchEvent<?> watchEvent : watchKey.pollEvents()) {
						final WatchEvent.Kind<?> kind = watchEvent.kind();
						if (kind == StandardWatchEventKinds.OVERFLOW) {
							continue;
						}
						final Path path = (Path) watchEvent.context();
						final Path parent = (Path) watchKey.watchable();
						final Path absolutePath = parent.resolve(path);
						try {
							final FileChannel fileChannel = FileChannel.open(absolutePath, _options);
							if (fileChannel.tryLock() != null) {
								submit(new Callable<Void>() {

									@Override
									public Void call() throws Exception {
										Context context = _workerPool.getContext();
										ESBMessage msg = new ESBMessage(BodyType.INVALID, null);
										msg.getVariables().put(ESBConstants.FileEventKind, kind.toString());
										msg.getVariables().put(ESBConstants.ContextPath, parent.toString());
										try (InputStream inputStream = Channels.newInputStream(fileChannel)) {
											fillESBMessage(msg, inputStream, path.toString());
											processInternal(context, msg);
											moveFile(context, msg, absolutePath, _move);
										} catch (Exception e) {
											logger.error("Exception processing file " + absolutePath, e);
											moveFile(context, msg, absolutePath, _moveOnError);
										} finally {
											_workerPool.releaseContext(context);
										}
										return null;
									}
								});
							} else {
								logger.warn("File could not be locked " + absolutePath);
								fileChannel.close();
							}
						} catch (IOException e) {
							logger.warn("File could not be opened " + absolutePath, e);
						}
					}
					if (!watchKey.reset()) {
						throw new IOException("Watchkey is no longer valid");
					}
				}
			}
		} catch (IOException e) {
			logger.error("Aborted " + getUri(), e);
		} catch (InterruptedException e) {
			// cancelled
		}
	}

	private static void fillESBMessage(ESBMessage msg, InputStream inputStream, String filename) throws Exception {
		msg.getVariables().put(ESBConstants.filename, filename);
		if ("zip".equals(IOUtils.getExt(filename))) {
			try (ZipInputStream zis = new ZipInputStream(inputStream)) {
				ZipEntry entry;
				while ((entry = zis.getNextEntry()) != null) {
					if (!entry.isDirectory()) {
						int i = entry.getName().lastIndexOf('/');
						String name = i < 0 ? entry.getName() : entry.getName().substring(i + 1);
						if ("xml".equals(IOUtils.getExt(name))) {
							msg.reset(BodyType.BYTES, IOUtils.copy(zis));
						} else {
							msg.addAttachment(name, MimeHelper.guessContentTypeFromName(name), IOUtils.copy(zis), name);
						}
					}
				}
			}
		} else {
			msg.reset(BodyType.INPUT_STREAM, inputStream);
		}
	}

	private void moveFile(Context context, ESBMessage msg, Path srcPath, String dest) {
		try {
			if (dest != null) {
				dest = (String) _startAction.bindVariable(dest, context, msg);
				Files.move(srcPath, Paths.get(dest), StandardCopyOption.ATOMIC_MOVE);
			}
		} catch (Exception e) {
			logger.error("Could not move file " + srcPath + " to " + dest, e);
		}
	}

}
