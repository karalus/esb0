package com.artofarc.esb.action;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Enumeration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.Queue;
import jakarta.jms.QueueBrowser;
import jakarta.jms.Session;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.ActiveMQSslConnectionFactory;
import org.junit.Test;

import com.artofarc.esb.AbstractESBTest;
import com.artofarc.esb.ConsumerPort;
import com.artofarc.esb.context.WorkerPool;
import com.artofarc.esb.jms.JMSConnectionData;
import com.artofarc.esb.jms.JMSConsumer;
import com.artofarc.esb.message.BodyType;
import com.artofarc.esb.message.ESBMessage;

public class WorkerPoolTest extends AbstractESBTest {

	@Test
	public void testWPNotFinished() throws Exception {
		WorkerPool workerPool = new WorkerPool(getGlobalContext(), "testWp", 2, 2, Thread.NORM_PRIORITY, 0, 2, true, true);
		//workerPool.close();
		Future<Boolean> future = workerPool.getExecutorService().submit(() -> { System.out.println("Hello"); Thread.sleep(100); System.out.println("Hello again"); return true; });
		Thread.sleep(50);
//		if (!future.isDone()) {
//			System.out.println(future.cancel(false));
//			System.out.println(future.isDone());
//		}
		future.get();
		System.out.println("Goodbye");
		workerPool.close();
		Thread.sleep(1000);
	}

	@Test
	public void testWP() throws Exception {
		WorkerPool workerPool = new WorkerPool(getGlobalContext(), "testWp", 0, 2, Thread.NORM_PRIORITY, 0, 0, true, true);
		//workerPool.close();
		Future<?> future = workerPool.getExecutorService().submit(() -> { System.out.println("Hello"); });
		future.get();
		System.out.println("Goodbye");
		workerPool.close();
		Thread.sleep(1000);
	}

	@Test
	public void testSchedule() throws Exception {
		WorkerPool workerPool = getGlobalContext().getDefaultWorkerPool();
		ScheduledFuture<?> future = workerPool.getScheduledExecutorService().scheduleAtFixedRate(() -> {
			System.out.println("Begin");
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			System.out.println("end");
		}, 500, 5000, TimeUnit.MILLISECONDS);
		System.out.println("Delay: " + future.getDelay(TimeUnit.MILLISECONDS));
		Thread.sleep(750);
		System.out.println("Delay: " + future.getDelay(TimeUnit.MILLISECONDS));
//		Thread.sleep(750);
//		System.out.println("Delay: " + future.getDelay(TimeUnit.MILLISECONDS));
		System.out.println("Cancel: " + future.cancel(false));
		try {
			future.get(2000, TimeUnit.MILLISECONDS);
		} catch (CancellationException e) {
			System.out.println("get");
		}
		Thread.sleep(750);
//		for (int i = 0; i < 100; ++i) {
//			System.out.println(future.isDone());
//			Thread.sleep(100);
//		}
	}

	
	@Test
	public void testScheduleAt() throws Exception {
		WorkerPool workerPool = getGlobalContext().getDefaultWorkerPool();
		ScheduledFuture<?> future = workerPool.getScheduledExecutorService().schedule(() -> {
			System.out.println("Begin");
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			System.out.println("end");
//			throw new RuntimeException();
		}, 500, TimeUnit.MILLISECONDS);
		Thread.sleep(750);
		//System.out.println("Cancel: " + future.cancel(false));
		//future.get(20, TimeUnit.MILLISECONDS);
	}

	@Test
	public void testResize() throws Exception {
		WorkerPool workerPool = getGlobalContext().getDefaultWorkerPool();
		workerPool.tryUpdate(150, 200, Thread.NORM_PRIORITY, 0, 2, true, true);
		workerPool.tryUpdate(20, 20, Thread.NORM_PRIORITY, 0, 2, true, true);
	}

}
