<%@page import="java.util.*"%>
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
<h2>ESB Zero - A lightweight service gateway (Version <%=application.getAttribute(ESBServletContextListener.VERSION)%> build time <%=application.getAttribute(ESBServletContextListener.BUILD_TIME)%>)</h2>
<%
	GlobalContext globalContext = (GlobalContext) application.getAttribute(ESBServletContextListener.CONTEXT);
	if (request.getPathInfo() == null) {
		if (!FileSystem.environment.equals("default")) {
%>
	<h3>Environment: <%=FileSystem.environment%></h3>
<%
		}
%>
<br>HttpServices:
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Path</b></td><td><b>Uri</b></td><td><b>PoolSize</b></td><td><b>Enabled</b></td><td><b>Delete</b></td></tr> 
<%
		List<String> list = new ArrayList<String>(globalContext.getHttpServicePaths());
		Collections.sort(list);
		for (String path : list) {
		   HttpConsumer consumerPort = globalContext.getHttpService(path);
		   %>
		   <tr>
		    <td><%=path%></td><td><a href="<%=request.getContextPath() + request.getServletPath() + consumerPort.getUri()%>"><%=consumerPort.getUri()%></a></td>
		    <td><%=consumerPort.getPoolSize()%></td>
		    <td><form method="post" action="admin/deploy<%=consumerPort.getUri()%>"><input type="submit" value="<%=consumerPort.isEnabled()%>"/></form></td>
		    <td><form action="admin/deploy<%=consumerPort.getUri()%>" onsubmit="return confirm('Are you sure to delete this service?');"><input type="submit" value="delete"/><input type="hidden" name="delete"/></form></td>
		   </tr>
		   <%
		}
%>
</table>
<br>RestServices:
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Path</b></td><td><b>Uri</b></td><td><b>PoolSize</b></td><td><b>Enabled</b></td><td><b>Delete</b></td></tr> 
<%
		for (String path : globalContext.getRESTServicePaths()) {
		   HttpConsumer consumerPort = globalContext.getHttpService(path);
		   %>
		   <tr>
		    <td><%=path%>*</td><td><a href="<%=request.getContextPath() + request.getServletPath() + consumerPort.getUri()%>"><%=consumerPort.getUri()%></a></td>
		    <td><%=consumerPort.getPoolSize()%></td>
		    <td><form method="post" action="admin/deploy<%=consumerPort.getUri()%>"><input type="submit" value="<%=consumerPort.isEnabled()%>"/></form></td>
		    <td><form action="admin/deploy<%=consumerPort.getUri()%>" onsubmit="return confirm('Are you sure to delete this service?');"><input type="submit" value="delete"/><input type="hidden" name="delete"/></form></td>
		   </tr>
		   <%
		}
%>
</table>
<br>JMSServices:
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Key</b></td><td><b>Uri</b></td><td><b>WorkerCount</b></td><td><b>Enabled</b></td><td><b>Delete</b></td></tr> 
<%
		for (JMSConsumer jmsConsumer : globalContext.getJMSConsumers()) {
		   %>
		   <tr>
		    <td><%=jmsConsumer.getKey()%></td>
		    <td><a href="<%=request.getContextPath() + request.getServletPath() + jmsConsumer.getUri()%>"><%=jmsConsumer.getUri()%></a></td>
		    <td><%=jmsConsumer.getWorkerCount()%></td>
		    <td><form method="post" action="admin/deploy<%=jmsConsumer.getUri()%>"><input type="submit" value="<%=jmsConsumer.isEnabled()%>"/></form></td>
		    <td><form action="admin/deploy<%=jmsConsumer.getUri()%>" onsubmit="return confirm('Are you sure to delete this service?');"><input type="submit" value="delete"/><input type="hidden" name="delete"/></form></td>
		   </tr>
		   <%
		}
%>
</table>
<br>TimerServices:
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Uri</b></td><td><b>Enabled</b></td><td><b>Delete</b></td></tr> 
<%
		for (ConsumerPort consumerPort : globalContext.getTimerServices()) {
		   %>
		   <tr>
		    <td><a href="<%=request.getContextPath() + request.getServletPath() + "/" + consumerPort.getUri()%>"><%=consumerPort.getUri()%></a></td>
		    <td><form method="post" action="admin/deploy<%=consumerPort.getUri()%>"><input type="submit" value="<%=consumerPort.isEnabled()%>"/></form></td>
		    <td><form action="admin/deploy<%=consumerPort.getUri()%>" onsubmit="return confirm('Are you sure to delete this service?');"><input type="submit" value="delete"/><input type="hidden" name="delete"/></form></td>
		   </tr>
		   <%
		}
%>
</table>
<br>WorkerPools:
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Uri</b></td><td><b>Active Threads</b></td><td><b>Size of work queue</b></td><td><b>Running Threads</b></td></tr> 
<%
		for (WorkerPool workerPool : globalContext.getWorkerPools()) {
		   %>
		   <tr><td><a href="<%=request.getContextPath() + request.getServletPath() + workerPool.getName() + "." + WorkerPoolArtifact.FILE_EXTENSION%>"><%=workerPool.getName()%></a></td><td><%=workerPool.getActiveThreads().size()%></td><td><%=workerPool.getQueueSize()%></td><td><%=workerPool.getRunningThreadsCount()%></td></tr>
		   <%
		}
%>
</table>
<br>HttpEndpoints:
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Name</b></td><td><b>Addresses</b></td><td><b>Total in use</b></td></tr> 
<%
		for (Map.Entry<HttpEndpoint, HttpUrlSelector> entry : globalContext.getHttpEndpointRegistry().getHttpEndpoints()) {
		   %>
		   <tr><td><%=entry.getKey().getName()%></td><td><%=entry.getKey().getHttpUrls()%></td><td><%=entry.getValue() != null ? entry.getValue().getInUseTotal() : "N/A"%></td></tr>
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
   Artifact a = globalContext.getFileSystem().getArtifact(request.getPathInfo());
   if (a instanceof Directory) {
%>
<br>Filesystem:
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Name</b></td></tr>
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
		<a href="<%=request.getContextPath()%>/admin">Admin UI Home</a><br>
	   <br>Artifact:
	   <table border="1"><tr bgcolor="#EEEEEE"><td><b>Property</b></td><td><b>Value</b></td></tr>
		   <tr><td>Name</td><td><%=a.getName()%></td></tr>
		   <tr><td>Validated</td><td><%=a.isValidated()%></td></tr>
		   <tr><td>Modified</td><td><%=new java.util.Date(a.getModificationTime())%></td></tr>
		   <%
		   	if (a instanceof SchemaArtifact) {
		   %>
				   <tr><td>Namespace</td><td><%=((SchemaArtifact) a).getNamespace()%></td></tr>
		   <%
		   	}
		   %>
	   </table>
	   <br>
	   <table border="1"><tr bgcolor="#EEEEEE"><td><b>Referenced</b></td></tr>
	   <%
	   	   for (String r : a.getReferenced()) {
	   %>
	  	<tr><td><a href="<%=request.getContextPath() + request.getServletPath() + r%>"><%=r%></a></td></tr>
	   <%
	       }
	   %>
	   </table>
	   <br>
	   <table border="1"><tr bgcolor="#EEEEEE"><td><b>ReferencedBy</b></td></tr>
	   <%
	   	   for (String r : a.getReferencedBy()) {
	   %>
	  	<tr><td><a href="<%=request.getContextPath() + request.getServletPath() + r%>"><%=r%></a></td></tr>
	   <%
	       }
	   %>
	   </table>
	   <%
			if (a.getContentType().startsWith("text/")) {
				String content = new String(com.artofarc.util.StreamUtils.copy(a.getContentAsStream())).replace("&", "&amp;");
		%>
			<br>
			<form action="<%=request.getContextPath() + "/admin/deploy" + pathInfo%>" enctype="text/plain" method="POST">
				<textarea name="content" rows="50" cols="200" spellcheck="false"<%if (a.getModificationTime() == 0) {%> readonly<%}%>><%=content%></textarea>
				<input type="submit" value="Change">
			</form>
		<%
			}
   }
%>
</body>
</html>
