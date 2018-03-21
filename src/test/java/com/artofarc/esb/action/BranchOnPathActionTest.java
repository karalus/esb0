package com.artofarc.esb.action;

import java.util.TreeMap;

import org.junit.Test;

import com.artofarc.esb.action.BranchOnPathAction.PathTemplate;

public class BranchOnPathActionTest {
	
   @Test
   public void testPathTemplate() throws Exception {
   	TreeMap<PathTemplate, String> map = new TreeMap<>();
   	
   	map.put(new PathTemplate("/orders/{id}"), null);
   	map.put(new PathTemplate("/orders"), null);
   	map.put(new PathTemplate("/customers/{id}"), null);
   	map.put(new PathTemplate("/customers/{id}/orders"), null);
   	
   	for (PathTemplate pathTemplate : map.keySet()) {
			System.out.println(pathTemplate);
			System.out.println(pathTemplate.match("/orders"));
			System.out.println(pathTemplate.match("/orders/"));
			System.out.println(pathTemplate.match("/orders/4711"));
			System.out.println(pathTemplate.match("/orders/4711/etc"));
			System.out.println(pathTemplate.match("/orders//"));
		}
   }

}
