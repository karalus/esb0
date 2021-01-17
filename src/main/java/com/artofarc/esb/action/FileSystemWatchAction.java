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
package com.artofarc.esb.action;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.mail.MessagingException;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;
import com.artofarc.esb.message.MimeHelper;
import com.artofarc.esb.resource.DirWatchServiceFactory;
import com.artofarc.util.IOUtils;

public class FileSystemWatchAction extends Action {

	private static final String INPUT_STREAM = "InputStream";

	private final long _timeout;
	private final String _workerPool;
	private final Action _spawn;
	private final List<String> _dirs;
	private final String _move, _moveOnError;
	private final StandardOpenOption[] _options;

	public FileSystemWatchAction(List<String> dirs, String move, String moveOnError, long timeout, String workerPool, Action spawn) throws IOException {
		_timeout = timeout;
		_workerPool = workerPool;
		_spawn = spawn;
		_dirs = dirs;
		for (String dir : dirs) {
			if (!new File(dir).isDirectory()) {
				throw new IOException("is not an existing directory " + dir);
			}
		}
		_move = move;
		_moveOnError = moveOnError;
		if (move != null || moveOnError != null) {
			_options = new StandardOpenOption[] { StandardOpenOption.READ, StandardOpenOption.WRITE };
		} else {
			_options = new StandardOpenOption[] { StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE };
		}
	}

	@Override
	protected long getTimeGaugeThreshold() {
		return Long.MAX_VALUE;
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		DirWatchServiceFactory resourceFactory = context.getGlobalContext().getResourceFactory(DirWatchServiceFactory.class);
		WatchService watchService = resourceFactory.getResource(_dirs);
		WatchKey watchKey = watchService.poll(_timeout, TimeUnit.MILLISECONDS);
		if (watchKey != null) {
			// File ready?
			Thread.sleep(50L);
			LinkedHashMap<Future<ESBMessage>, ESBMessage> futures = new LinkedHashMap<>();
			for (WatchEvent<?> watchEvent : watchKey.pollEvents()) {
				WatchEvent.Kind<?> kind = watchEvent.kind();
				if (kind == StandardWatchEventKinds.OVERFLOW) {
					continue;
				}
				Path path = (Path) watchEvent.context();
				Path parent = (Path) watchKey.watchable();
				Path absolutePath = parent.resolve(path);
				try {
					FileChannel fileChannel = FileChannel.open(absolutePath, _options);
					if (fileChannel.tryLock() != null) {
						InputStream inputStream = Channels.newInputStream(fileChannel);
						ESBMessage msg = createESBMessage(inputStream, path.toString());
						msg.getVariables().put(INPUT_STREAM, inputStream);
						msg.getVariables().put(ESBConstants.FileEventKind, kind.toString());
						msg.getVariables().put(ESBConstants.ContextPath, parent.toString());
						msg.getVariables().put(ESBConstants.PathInfo, absolutePath);
						for (;;) {
							try {
								futures.put(SpawnAction.submit(context, msg, _workerPool, _spawn, Collections.<Action> emptyList(), false, true), msg);
								break;
							} catch (RejectedExecutionException e) {
								logger.warn("Could not spawn to worker pool " + _workerPool);
								Thread.sleep(100L);
							}
						}
					} else {
						logger.warn("File could not be locked " + absolutePath);
						fileChannel.close();
					}
				} catch (IOException e) {
					logger.warn("File could not be opened" + absolutePath, e);
				}
			}
			if (!watchKey.reset()) {
				resourceFactory.close(_dirs);
			}
			for (Future<ESBMessage> future : futures.keySet()) {
				ESBMessage msg = futures.get(future);
				Path srcPath = msg.getVariable(ESBConstants.PathInfo);
				try {
					SpawnAction.join(context, message, future, false);
					if (_move != null) {
						String destPath = (String) bindVariable(_move, context, msg);
						Files.move(srcPath, Paths.get(destPath), StandardCopyOption.ATOMIC_MOVE);
					}
				} catch (Exception e) {
					logger.error("Exception processing file " + srcPath, e);
					if (_moveOnError != null) {
						String destPath = (String) bindVariable(_moveOnError, context, msg);
						Files.move(srcPath, Paths.get(destPath), StandardCopyOption.ATOMIC_MOVE);
					}
				} finally {
					msg.<InputStream> getVariable(INPUT_STREAM).close();
				}
			}
		}
	}

	private static ESBMessage createESBMessage(InputStream inputStream, String filename) throws IOException, MessagingException {
		ESBMessage msg;
		if ("zip".equals(IOUtils.getExt(filename))) {
			msg = new ESBMessage(BodyType.INVALID, null);
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
			msg = new ESBMessage(BodyType.INPUT_STREAM, inputStream);
		}
		msg.getVariables().put(ESBConstants.filename, filename);
		return msg;
	}

}
