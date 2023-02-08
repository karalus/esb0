/*
 * Copyright 2023 Andre Karalus
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
package com.artofarc.esb;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class Trend {

	private final long _significance;
	private final AtomicLong _current = new AtomicLong(), _lastUpdate = new AtomicLong();

	public Trend(long significance) {
		_significance = TimeUnit.SECONDS.toNanos(significance);
	}

	public long getCurrent() {
		return _current.get();
	}

	public long accumulateAndGet(long value) {
		final long nanoTime = System.nanoTime();
		// Two updates are not atomic, but it's only statistic so that it does not really matter 
		final long timeDiff = nanoTime - _lastUpdate.getAndSet(nanoTime);
		if (timeDiff > 0) {
			final long significance = _significance / timeDiff;
			return _current.accumulateAndGet(value, (prev, x) -> (prev * significance + x) / (significance + 1));
		} else {
			return _current.get();
		}
	}

}
