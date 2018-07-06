<%@page import="com.artofarc.esb.servlet.HttpConsumer"%>
<html>
<body>
<%@ page import = "com.artofarc.esb.context.PoolContext" %>
<%@ page import = "com.artofarc.esb.ConsumerPort" %>
<%@ page import = "com.artofarc.esb.servlet.HttpConsumer" %>
<%@ page import = "com.artofarc.esb.jms.JMSConsumer" %>
<%@ page import = "com.artofarc.esb.context.WorkerPool" %>
<%@ page import = "com.artofarc.esb.artifact.*" %>
<%@ page import = "com.artofarc.esb.service.*" %>
<%@ page import = "javax.xml.bind.JAXBElement" %>
<h2>ESB Zero - A lightweight service gateway</h2>
<%!
	public String toDOTNode(JAXBElement<?> e, int i) {
		return e.getName().getLocalPart() + "_" + i;// + "[label=\"" + e.getName().getLocalPart() + "\"]";
	}
%>
<%
	PoolContext poolContext = (PoolContext) application.getAttribute("WebContainerPoolContext");
	if (request.getPathInfo() == null) {
%>
<br>HttpServices:
<table border="1"><tr bgcolor="#EEEEEE"><td align="center"><b>Path</b></td><td align="center"><b>Uri</b></td><td align="center"><b>PoolSize</b></td><td align="center"><b>Enabled</b></td></tr> 
<%
		for (String path : poolContext.getGlobalContext().getHttpServicePaths()) {
		   HttpConsumer consumerPort = poolContext.getGlobalContext().getHttpService(path);
		   %>
		   <tr><td><%=path%></td><td><a href="<%=request.getContextPath() + request.getServletPath() + consumerPort.getUri()%>"><%=consumerPort.getUri()%></a></td><td><%=consumerPort.getPoolSize()%></td><td><form method="post" action="admin/deploy<%=consumerPort.getUri()%>"><input type="submit" value="<%=consumerPort.isEnabled()%>"/></form></tr>
		   <%
		}
%>
</table>
<br>JMSServices:
<table border="1"><tr bgcolor="#EEEEEE"><td align="center"><b>Key</b></td><td align="center"><b>Uri</b></td><td align="center"><b>Enabled</b></td></tr> 
<%
		for (JMSConsumer jmsConsumer : poolContext.getGlobalContext().getJMSConsumers()) {
		   %>
		   <tr><td><%=jmsConsumer.getKey()%></td><td><a href="<%=request.getContextPath() + request.getServletPath() + jmsConsumer.getUri()%>"><%=jmsConsumer.getUri()%></a></td><td><form method="post" action="admin/deploy<%=jmsConsumer.getUri()%>"><input type="submit" value="<%=jmsConsumer.isEnabled()%>"/></form></tr>
		   <%
		}
%>
</table>
<br>TimerServices:
<table border="1"><tr bgcolor="#EEEEEE"><td align="center"><b>Uri</b></td><td align="center"><b>Enabled</b></td></tr> 
<%
		for (ConsumerPort consumerPort : poolContext.getGlobalContext().getTimerServices()) {
		   %>
		   <tr><td><a href="<%=request.getContextPath() + request.getServletPath() + "/" + consumerPort.getUri()%>"><%=consumerPort.getUri()%></a></td><td><form method="post" action="admin/deploy<%=consumerPort.getUri()%>"><input type="submit" value="<%=consumerPort.isEnabled()%>"/></form></tr>
		   <%
		}
%>
</table>
<br>WorkerPools:
<table border="1"><tr bgcolor="#EEEEEE"><td align="center"><b>Uri</b></td><td align="center"><b>ActiveCount</b></td><td align="center"><b>Size work queue</b></td></tr> 
<%
		for (WorkerPool workerPool : poolContext.getGlobalContext().getWorkerPools()) {
			ThreadGroup threadGroup = workerPool.getThreadGroup();
			java.util.concurrent.BlockingQueue<Runnable> workQueue = workerPool.getWorkQueue();
			String sizeWorkQueue = workQueue != null ? "" + workQueue.size() : "n/a";
		   %>
		   <tr><td><a href="<%=request.getContextPath() + request.getServletPath() + threadGroup.getName() + "." + WorkerPoolArtifact.FILE_EXTENSION%>"><%=threadGroup.getName()%></a></td><td><%=threadGroup.activeCount()%></td><td><%=sizeWorkQueue%></td></tr>
		   <%
		}
%>
</table>
<br>Upload Service-JAR:
<form action="admin/deploy" enctype="multipart/form-data" method="POST">
	<input type="file" name="file">
	<input type="submit" value="Upload">
</form>
<%
	}
%>
<br>
<%
   String pathInfo = request.getPathInfo() != null ? request.getPathInfo() : "";
   Artifact a = poolContext.getGlobalContext().getFileSystem().getArtifact(request.getPathInfo());
   if (a instanceof Directory) {
%>
<br>Filesystem:
<table border="1"><tr bgcolor="#EEEEEE"><td align="center"><b>Name</b></td></tr>
<%
	   for (String name : ((Directory) a).getArtifacts().keySet()) {
%>
  <tr><td><a href="<%=request.getContextPath() + request.getServletPath() + pathInfo + "/" + name%>"><%=name%></a></td></tr>
<%
       }
%>
</table>
<%
   } else if (a != null) {
	   %>
	   <br>Artifact:
	   <table border="1"><tr bgcolor="#EEEEEE"><td align="center"><b>Property</b></td><td align="center"><b>Value</b></td></tr>
		   <tr><td>Name</td><td><%=a.getName()%></td></tr>
		   <tr><td>Validated</td><td><%=a.isValidated()%></td></tr>
		   <tr><td>Modified</td><td><%=new java.util.Date(a.getModificationTime())%></td></tr>
	   </table>
	   <table border="1"><tr bgcolor="#EEEEEE"><td align="center"><b>Referenced</b></td></tr>
	   <%
	   	   for (String r : a.getReferenced()) {
	   %>
	  	<tr><td><a href="<%=request.getContextPath() + request.getServletPath() +  r%>"><%=r%></a></td></tr>
	   <%
	       }
	   %>
	   </table>
	   <br>
	   <table border="1"><tr bgcolor="#EEEEEE"><td align="center"><b>ReferencedBy</b></td></tr>
	   <%
	   	   for (String r : a.getReferencedBy()) {
	   %>
	  	<tr><td><a href="<%=request.getContextPath() + request.getServletPath() + r%>"><%=r%></a></td></tr>
	   <%
	       }
	   %>
	   </table>
	   <object data="<%=request.getContextPath() + request.getServletPath() + "/deploy" + a.getURI()%>" type="text/plain" width="1600" style="height: 800px"></object>
	   <%
   }
%>
</body>
</html>
