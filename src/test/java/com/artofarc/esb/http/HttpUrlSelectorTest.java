package com.artofarc.esb.http;

import static org.junit.Assert.*;

import java.net.Proxy;
import java.util.ArrayList;
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

}
