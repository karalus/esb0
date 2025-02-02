package com.artofarc.esb.http;

import static org.junit.Assert.*;

import java.net.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.artofarc.esb.AbstractESBTest;

public class HttpUrlSelectorTest extends AbstractESBTest {

	@Test
	public void testRoundRobin() throws Exception {
		List<HttpUrl> list = new ArrayList<>();
		for (int i = 0; i < 6; ++i) {
			list.add(new HttpUrl("http://localhost:" + (9001 + i), 1, true));
		}
		HttpEndpoint httpEndpoint = new HttpEndpoint(null, list, true, null, null, 1000, 5, 120, new HttpCheckAlive(), System.currentTimeMillis(), Proxy.NO_PROXY, null, null);
		Http1UrlSelector httpUrlSelector = new Http1UrlSelector(httpEndpoint , getGlobalContext().getDefaultWorkerPool());
		int oldpos = 5;
		for (int i = 0; i < 20; ++i) {
			int pos = httpUrlSelector.computeNextPos(httpEndpoint);
			System.out.println(pos);
			assertTrue((6 + pos - oldpos) % 6 == 1);
			oldpos = pos;
		}
	}

	@Test
	public void testRoundRobinSingleThreaded() throws Exception {
		List<HttpUrl> list = new ArrayList<>();
		for (int i = 0; i < 6; ++i) {
			list.add(new HttpUrl("http://localhost:" + (9001 + i), 1, true));
		}
		HttpEndpoint httpEndpoint = new HttpEndpoint(null, list, false, null, null, 1000, 5, 120, new HttpCheckAlive(), System.currentTimeMillis(), Proxy.NO_PROXY, null, null);
		Http1UrlSelector httpUrlSelector = new Http1UrlSelector(httpEndpoint , getGlobalContext().getDefaultWorkerPool());
		for (int i = 0; i < 6; i += 2) {
			// every even position is in use
			httpUrlSelector.new HttpUrlConnection(httpEndpoint, i, null, null);
		}
		for (int i = 0; i < 20; ++i) {
			int pos = httpUrlSelector.computeNextPos(httpEndpoint);
			System.out.println(pos);
			assertTrue(pos % 2 == 1);
		}
	}

	@Test
	public void testRoundRobinSingleThreadedAllInUse() throws Exception {
		List<HttpUrl> list = new ArrayList<>();
		for (int i = 0; i < 6; ++i) {
			list.add(new HttpUrl("http://localhost:" + (9001 + i), 1, true));
		}
		HttpEndpoint httpEndpoint = new HttpEndpoint(null, list, false, null, null, 1000, 5, 120, new HttpCheckAlive(), System.currentTimeMillis(), Proxy.NO_PROXY, null, null);
		Http1UrlSelector httpUrlSelector = new Http1UrlSelector(httpEndpoint , getGlobalContext().getDefaultWorkerPool());
		for (int i = 0; i < 6; ++i) {
			httpUrlSelector.new HttpUrlConnection(httpEndpoint, i, null, null);
		}
		int oldpos = 5;
		for (int i = 0; i < 20; ++i) {
			int pos = httpUrlSelector.computeNextPos(httpEndpoint);
			System.out.println(pos);
			assertTrue((6 + pos - oldpos) % 6 == 1);
			oldpos = pos;
		}
	}

	@Test
	public void testHttpUrlChanged() throws Exception {
		HttpUrl httpUrl1 = new HttpUrl("http://localhost:443", 1, true);
		HttpUrl httpUrl2 = new HttpUrl("https://localhost", 1, true);
		assertNotEquals(httpUrl1, httpUrl2);
	}

	@Test
	public void testDate() {
		long epochSeconds = HttpConstants.parseHttpDate("Wed, 28 Aug 2024 08:43:34 GMT");
		assertEquals(1724834614, epochSeconds);
	}

	@Test
	public void testRetryAfter() {
		HttpCheckAlive httpCheckAlive = new HttpCheckAlive();
		httpCheckAlive.isAlive(503, (h) -> HttpConstants.toHttpDate((System.currentTimeMillis() + 999) / 1000));
		Integer consumeRetryAfter = httpCheckAlive.consumeRetryAfter();
		assertEquals(1, consumeRetryAfter.intValue());
	}

	@Test
	public void testActivePassive() throws Exception {
		List<HttpUrl> list = new ArrayList<>();
		for (int i = 0; i < 2; ++i) {
			list.add(new HttpUrl("http://localhost:" + (9001 + i), 1, (i & 1) == 0));
		}
		HttpEndpoint httpEndpoint = new HttpEndpoint(null, list, true, null, null, 1000, list.size() - 1, 120, new HttpCheckAlive(), System.currentTimeMillis(), Proxy.NO_PROXY, null, null);
		Http1UrlSelector httpUrlSelector = new Http1UrlSelector(httpEndpoint , getGlobalContext().getDefaultWorkerPool());
		httpUrlSelector.setActive(httpEndpoint, 0, false);
		for (int i = 0; i < 30; ++i) {
			if (i > 9) {
				httpUrlSelector.setActive(httpEndpoint, 0, true);
			}
			if (i > 19) {
				httpUrlSelector.setActive(httpEndpoint, 1, true);
			}
			int pos = httpUrlSelector.computeNextPos(httpEndpoint);
			System.out.println(pos);
		}
		System.out.println(Arrays.asList(httpUrlSelector.getHttpEndpointStates()));
	}

}
