<%@page import="java.util.Map.Entry"%>
<%@page import="com.artofarc.esb.context.PoolContext"%>
<%@page import="com.artofarc.esb.context.GlobalContext"%>
<%@page import="com.artofarc.esb.ConsumerPort"%>
<%@page import="com.artofarc.esb.servlet.ESBServletContextListener"%>
<%@page import="com.artofarc.esb.servlet.HttpConsumer"%>
<%@page import="com.artofarc.esb.jms.JMSConsumer"%>
<%@page import="com.artofarc.esb.context.WorkerPool"%>
<%@page import="com.artofarc.esb.http.HttpEndpoint"%>
<%@page import="com.artofarc.esb.http.HttpUrlSelector"%>
<%@page import="com.artofarc.esb.artifact.*"%>
<html>
<body>
<h2>ESB Zero - A lightweight service gateway (v<%=application.getAttribute(ESBServletContextListener.VERSION)%>)</h2>
<%
	GlobalContext globalContext = ((PoolContext) application.getAttribute(ESBServletContextListener.POOL_CONTEXT)).getGlobalContext();
	if (request.getPathInfo() == null) {
%>
<br>HttpServices:
<table border="1"><tr bgcolor="#EEEEEE"><td align="center"><b>Path</b></td><td align="center"><b>Uri</b></td><td align="center"><b>PoolSize</b></td><td align="center"><b>Enabled</b></td></tr> 
<%
		for (String path : globalContext.getHttpServicePaths()) {
		   HttpConsumer consumerPort = globalContext.getHttpService(path);
		   %>
		   <tr><td><%=path%></td><td><a href="<%=request.getContextPath() + request.getServletPath() + consumerPort.getUri()%>"><%=consumerPort.getUri()%></a></td><td><%=consumerPort.getPoolSize()%></td><td><form method="post" action="admin/deploy<%=consumerPort.getUri()%>"><input type="submit" value="<%=consumerPort.isEnabled()%>"/></form></tr>
		   <%
		}
%>
</table>
<br>JMSServices:
<table border="1"><tr bgcolor="#EEEEEE"><td align="center"><b>Key</b></td><td align="center"><b>Uri</b></td><td align="center"><b>Enabled</b></td></tr> 
<%
		for (JMSConsumer jmsConsumer : globalContext.getJMSConsumers()) {
		   %>
		   <tr><td><%=jmsConsumer.getKey()%></td><td><a href="<%=request.getContextPath() + request.getServletPath() + jmsConsumer.getUri()%>"><%=jmsConsumer.getUri()%></a></td><td><form method="post" action="admin/deploy<%=jmsConsumer.getUri()%>"><input type="submit" value="<%=jmsConsumer.isEnabled()%>"/></form></tr>
		   <%
		}
%>
</table>
<br>TimerServices:
<table border="1"><tr bgcolor="#EEEEEE"><td align="center"><b>Uri</b></td><td align="center"><b>Enabled</b></td></tr> 
<%
		for (ConsumerPort consumerPort : globalContext.getTimerServices()) {
		   %>
		   <tr><td><a href="<%=request.getContextPath() + request.getServletPath() + "/" + consumerPort.getUri()%>"><%=consumerPort.getUri()%></a></td><td><form method="post" action="admin/deploy<%=consumerPort.getUri()%>"><input type="submit" value="<%=consumerPort.isEnabled()%>"/></form></tr>
		   <%
		}
%>
</table>
<br>WorkerPools:
<table border="1"><tr bgcolor="#EEEEEE"><td align="center"><b>Uri</b></td><td align="center"><b>Active Threads</b></td><td align="center"><b>Size of work queue</b></td><td align="center"><b>Running Threads</b></td></tr> 
<%
		for (WorkerPool workerPool : globalContext.getWorkerPools()) {
		   %>
		   <tr><td><a href="<%=request.getContextPath() + request.getServletPath() + workerPool.getName() + "." + WorkerPoolArtifact.FILE_EXTENSION%>"><%=workerPool.getName()%></a></td><td><%=workerPool.getActiveThreads().size()%></td><td><%=workerPool.getQueueSize()%></td><td><%=workerPool.getRunningThreadsCount()%></td></tr>
		   <%
		}
%>
</table>
<br>HttpEndpoints:
<table border="1"><tr bgcolor="#EEEEEE"><td align="center"><b>Name</b></td><td align="center"><b>Addresses</b></td><td align="center"><b>Total in use</b></td></tr> 
<%
		for (Entry<HttpEndpoint, HttpUrlSelector> entry : globalContext.getHttpEndpointRegistry().getHttpEndpoints()) {
			if (entry.getValue() != null) {
			   %>
			   <tr><td><%=entry.getKey().getName()%></td><td><%=entry.getKey().getHttpUrls()%></td><td><%=entry.getValue().getInUseTotal()%></td></tr>
			   <%
				
			}
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
   Artifact a = globalContext.getFileSystem().getArtifact(request.getPathInfo());
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
