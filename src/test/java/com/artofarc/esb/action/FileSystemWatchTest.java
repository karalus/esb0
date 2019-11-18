package com.artofarc.esb.action;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.Test;

import com.artofarc.esb.AbstractESBTest;
import com.artofarc.esb.TimerService;

public class FileSystemWatchTest extends AbstractESBTest {

	@Test
	public void testTimerService() throws Exception {
		TimerService timerService = new TimerService(null, null, null, "seconds", 100, 0, false);
		MarkAction markAction = new MarkAction();
		timerService.setStartAction(markAction);
		assertFalse(markAction.isExecuted());
		timerService.init(context.getPoolContext().getGlobalContext());
		Thread.sleep(100);
		timerService.enable(false);
		assertTrue(markAction.isExecuted());
	}

	@Test
	public void testDetectChange() throws Exception {
		Path tempDirectory = Files.createTempDirectory("JUnit-");
		File dir = tempDirectory.toFile();
		dir.deleteOnExit();
		File inDir = new File(dir, "in");
		inDir.mkdir();
		inDir.deleteOnExit();
		File outDir = new File(dir, "out");
		outDir.mkdir();
		outDir.deleteOnExit();
		TimerService timerService = new TimerService(null, null, null, "seconds", 1, 0, false);
		FileSystemWatchAction action = new FileSystemWatchAction(Arrays.asList(new String[] { inDir.getPath() }), 90l, null, new FileAction(outDir.getPath()));
		timerService.setStartAction(action);
		timerService.init(context.getPoolContext().getGlobalContext());
		Thread.sleep(200);
		File outFile1 = new File(outDir, "test1");
		File outFile2 = new File(outDir, "test2");
		assertFalse(outFile1.exists());
		File inFile1 = new File(inDir, "test1");
		new FileOutputStream(inFile1).close();
		Thread.sleep(10);
		File inFile2 = new File(inDir, "test2");
		new FileOutputStream(inFile2).close();
		Thread.sleep(1000);
		assertTrue(outFile1.exists());
		assertFalse(inFile1.exists());
		outFile1.deleteOnExit();
		assertTrue(outFile2.exists());
		assertFalse(inFile2.exists());
		outFile2.deleteOnExit();
		timerService.enable(false);
	}

}
