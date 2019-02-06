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

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import java.util.logging.Level;

import com.artofarc.esb.context.Context;
import com.artofarc.esb.context.ExecutionContext;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBConstants;
import com.artofarc.esb.message.ESBMessage;

public class FileSystemWatchAction extends TerminalAction {

	private static final String INPUT_STREAM = "InputStream";

	private final long _timeout;
	private final String _workerPool;
	private final Action _spawn;
	private final WatchService _watchService;

	public FileSystemWatchAction(List<String> dirs, long timeout, String workerPool, Action spawn) throws IOException {
		_timeout = timeout;
		_workerPool = workerPool;
		_spawn = spawn;
		_watchService = FileSystems.getDefault().newWatchService();
		for (String dir : dirs) {
			Path path = Paths.get(dir);
			path.register(_watchService, StandardWatchEventKinds.ENTRY_CREATE);
		}
	}

	@Override
	protected void execute(Context context, ExecutionContext execContext, ESBMessage message, boolean nextActionIsPipelineStop) throws Exception {
		WatchKey watchKey = _watchService.poll(_timeout, TimeUnit.MILLISECONDS);
		if (watchKey != null) {
			LinkedHashMap<Future<ESBMessage>, WatchEvent<?>> futures = new LinkedHashMap<>();
			for (WatchEvent<?> watchEvent : watchKey.pollEvents()) {
				Path path = (Path) watchEvent.context();
				Path parent = (Path) watchKey.watchable();
				Path absolutePath = parent.resolve(path);
				FileChannel fileChannel = FileChannel.open(absolutePath, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE);
				if (fileChannel.tryLock() != null) {
					ESBMessage msg = new ESBMessage(BodyType.INPUT_STREAM, Channels.newInputStream(fileChannel));
					msg.getVariables().put(INPUT_STREAM, msg.getBody());
					msg.getVariables().put(ESBConstants.HttpMethod, watchEvent.kind().toString());
					msg.getVariables().put(ESBConstants.ContextPath, parent.toString());
					msg.getVariables().put(ESBConstants.PathInfo, path.toString());
					logger.fine("Absolute path " + absolutePath + ", kind: " + watchEvent.kind());
					for(;;) {
						try {
							futures.put(SpawnAction.submit(context, msg, _workerPool, _spawn, Collections.<Action> emptyList(), true), watchEvent);
							break;
						} catch (RejectedExecutionException e) {
							logger.warning("Could not spawn to worker pool " + _workerPool);
							Thread.sleep(100L);
						}
					}
				} else {
					logger.warning("File could not be locked " + absolutePath);
					fileChannel.close();
				}
			}
			watchKey.reset();
			for (Future<ESBMessage> future : futures.keySet()) {
				try {
					InputStream is = SpawnAction.join(context, message, future).getVariable(INPUT_STREAM);
					is.close();
				} catch (Exception e) {
					WatchEvent<?> watchEvent = futures.get(future);
					logger.log(Level.SEVERE, "Exception processing watchEvent " + watchEvent.context(), e);
				}
			}
		}
	}

}
