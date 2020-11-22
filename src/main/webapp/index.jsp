<%@page import="java.util.*"%>
<%@page import="com.artofarc.esb.context.GlobalContext"%>
<%@page import="com.artofarc.esb.ConsumerPort"%>
<%@page import="com.artofarc.esb.TimerService"%>
<%@page import="com.artofarc.esb.servlet.ESBServletContextListener"%>
<%@page import="com.artofarc.esb.servlet.HttpConsumer"%>
<%@page import="com.artofarc.esb.jms.JMSConsumer"%>
<%@page import="com.artofarc.esb.context.WorkerPool"%>
<%@page import="com.artofarc.esb.http.HttpEndpoint"%>
<%@page import="com.artofarc.esb.http.HttpUrlSelector"%>
<%@page import="com.artofarc.esb.artifact.*"%>
<html>
<head>
<style>
textarea, pre {
	-moz-tab-size: 4;
	-o-tab-size: 4;
	tab-size: 4;
}
</style>
</head>
<body>
<%
	GlobalContext globalContext = (GlobalContext) application.getAttribute(ESBServletContextListener.CONTEXT);
%>
<h2>ESB Zero - A lightweight service gateway (Version <%=globalContext.getVersion()%> build time <%=globalContext.getProperty(GlobalContext.BUILD_TIME)%>)</h2>
<%
	String pathInfo = request.getPathInfo();
	if (pathInfo == null) {
		if (!FileSystem.environment.equals("default")) {
%>
	<h3>Environment: <%=FileSystem.environment%></h3>
<%
		}
		String query = request.getQueryString();
		if (query != null) {
			%>
			<a href="<%=request.getContextPath()%>/admin">Admin UI Home</a><br>
			<%
		}
		switch (query != null ? query : "") {
		case "HttpServices":
			%>
<br>HttpServices:
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Path</b></td><td><b>Uri</b></td><td><b>PoolSize</b></td><td><b>Completed tasks</b></td><td><b>Enabled</b></td><td><b>Delete</b></td></tr> 
<%
		List<String> paths = new ArrayList<String>(globalContext.getHttpServicePaths());
		Collections.sort(paths);
		for (String path : paths) {
		   HttpConsumer consumerPort = globalContext.getHttpService(path);
		   %>
		   <tr>
		    <td><%=path%></td><td><a href="<%=request.getContextPath() + request.getServletPath() + consumerPort.getUri()%>"><%=consumerPort.getUri()%></a></td>
		    <td><%=consumerPort.getPoolSize()%></td>
		    <td><%=consumerPort.getCompletedTaskCount()%></td>
		    <td><form method="post" action="<%=ESBServletContextListener.ADMIN_SERVLET_PATH%><%=consumerPort.getUri()%>?HttpServices"><input type="submit" value="<%=consumerPort.isEnabled()%>"/></form></td>
		    <td><form action="<%=ESBServletContextListener.ADMIN_SERVLET_PATH%><%=consumerPort.getUri()%>" onsubmit="return confirm('Are you sure to delete \'<%=consumerPort.getUri()%>\'?');"><input type="submit" value="delete"/><input type="hidden" name="DELETE" value="HttpServices"/></form></td>
		   </tr>
		   <%
		}
%>
</table>
			<%
			break;
		case "MappedHttpServices":
			%>
<br>HttpServices with path mapping:
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Path</b></td><td><b>Uri</b></td><td><b>PoolSize</b></td><td><b>Completed tasks</b></td><td><b>Enabled</b></td><td><b>Delete</b></td></tr> 
<%
		for (String path : globalContext.getMappedHttpServicePaths()) {
		   HttpConsumer consumerPort = globalContext.getHttpService(path);
		   %>
		   <tr>
		    <td><%=path%>*</td><td><a href="<%=request.getContextPath() + request.getServletPath() + consumerPort.getUri()%>"><%=consumerPort.getUri()%></a></td>
		    <td><%=consumerPort.getPoolSize()%></td>
		    <td><%=consumerPort.getCompletedTaskCount()%></td>
		    <td><form method="post" action="<%=ESBServletContextListener.ADMIN_SERVLET_PATH%><%=consumerPort.getUri()%>?MappedHttpServices"><input type="submit" value="<%=consumerPort.isEnabled()%>"/></form></td>
		    <td><form action="<%=ESBServletContextListener.ADMIN_SERVLET_PATH%><%=consumerPort.getUri()%>" onsubmit="return confirm('Are you sure to delete \'<%=consumerPort.getUri()%>\'?');"><input type="submit" value="delete"/><input type="hidden" name="DELETE" value="MappedHttpServices"/></form></td>
		   </tr>
		   <%
		}
%>
</table>
			<%
			break;
		case "JMSServices":
			%>
<br>JMSServices:
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Key</b></td><td><b>Uri</b></td><td><b>WorkerCount</b></td><td><b>Completed tasks</b></td><td><b>Enabled</b></td><td><b>Delete</b></td></tr> 
<%
		List<JMSConsumer> jmsConsumers = new ArrayList<JMSConsumer>(globalContext.getJMSConsumers());
		Collections.sort(jmsConsumers);
		for (JMSConsumer jmsConsumer : jmsConsumers) {
		   %>
		   <tr>
		    <td><%=jmsConsumer.getKey()%></td>
		    <td><a href="<%=request.getContextPath() + request.getServletPath() + jmsConsumer.getUri()%>"><%=jmsConsumer.getUri()%></a></td>
		    <td><%=jmsConsumer.getWorkerCount()%></td>
		    <td><%=jmsConsumer.getCompletedTaskCount()%></td>
		    <td><form method="post" action="<%=ESBServletContextListener.ADMIN_SERVLET_PATH%><%=jmsConsumer.getUri()%>?JMSServices"><input type="submit" value="<%=jmsConsumer.isEnabled()%>"/></form></td>
		    <td><form action="<%=ESBServletContextListener.ADMIN_SERVLET_PATH%><%=jmsConsumer.getUri()%>" onsubmit="return confirm('Are you sure to delete \'<%=jmsConsumer.getUri()%>\'?');"><input type="submit" value="delete"/><input type="hidden" name="DELETE" value="JMSServices"/></form></td>
		   </tr>
		   <%
		}
%>
</table>
			<%
			break;
		case "TimerServices":
			%>
<br>TimerServices:
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Uri</b></td><td><b>Delay</b></td><td><b>Enabled</b></td><td><b>Delete</b></td></tr> 
<%
		for (TimerService consumerPort : globalContext.getTimerServices()) {
		   %>
		   <tr>
		    <td><a href="<%=request.getContextPath() + request.getServletPath() + consumerPort.getUri()%>"><%=consumerPort.getUri()%></a></td>
		    <td><%=consumerPort.getDelay() != null ? consumerPort.getDelay() + " " + consumerPort.getTimeUnit().toString().toLowerCase() : "N/A"%></td>
		    <td><form method="post" action="<%=ESBServletContextListener.ADMIN_SERVLET_PATH%><%=consumerPort.getUri()%>?TimerServices"><input type="submit" value="<%=consumerPort.isEnabled()%>"/></form></td>
		    <td><form action="<%=ESBServletContextListener.ADMIN_SERVLET_PATH%><%=consumerPort.getUri()%>" onsubmit="return confirm('Are you sure to delete \'<%=consumerPort.getUri()%>\'?');"><input type="submit" value="delete"/><input type="hidden" name="DELETE" value="TimerServices"/></form></td>
		   </tr>
		   <%
		}
%>
</table>
			<%
			break;
		case "InternalServices":
			%>
<br>InternalServices:
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Uri</b></td><td><b>ReferencedBy</b></td><td><b>Delete</b></td></tr> 
<%
		for (ConsumerPort consumerPort : globalContext.getInternalServices()) {
		   %>
		   <tr>
		    <td><a href="<%=request.getContextPath() + request.getServletPath() + consumerPort.getUri()%>"><%=consumerPort.getUri()%></a></td>
		    <td><%=globalContext.getFileSystem().getArtifact(consumerPort.getUri()).getReferencedBy().size()%></td>
		    <td><form action="<%=ESBServletContextListener.ADMIN_SERVLET_PATH%><%=consumerPort.getUri()%>" onsubmit="return confirm('Are you sure to delete \'<%=consumerPort.getUri()%>\'?');"><input type="submit" value="delete"/><input type="hidden" name="DELETE" value="InternalServices"/></form></td>
		   </tr>
		   <%
		}
%>
</table>
			<%
			break;
		case "WorkerPools":
			%>
<br>WorkerPools:
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Uri</b></td><td><b>Active Threads</b></td><td><b>Size of work queue</b></td><td><b>Running Threads</b></td><td><b>Completed tasks</b></td></tr> 
<%
		for (WorkerPool workerPool : globalContext.getWorkerPools()) {
		   %>
		   <tr><td><a href="<%=request.getContextPath() + request.getServletPath() + workerPool.getName() + "." + WorkerPoolArtifact.FILE_EXTENSION%>"><%=workerPool.getName()%></a></td><td><%=workerPool.getActiveThreads().size()%></td><td><%=workerPool.getQueueSize()%></td><td><%=workerPool.getRunningThreadsCount()%></td><td><%=workerPool.getCompletedTaskCount()%></td></tr>
		   <%
		}
%>
</table>
			<%
			break;
		case "HttpEndpoints":
			%>
<br>HttpEndpoints:
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Name</b></td><td><b>Addresses</b></td><td><b>Active</b></td><td><b>Total in use</b></td><td><b>Total connections</b></td></tr> 
<%
		for (Map.Entry<HttpEndpoint, HttpUrlSelector> entry : globalContext.getHttpEndpointRegistry().getHttpEndpoints()) {
			HttpUrlSelector httpUrl = entry.getValue();
		   %>
		   <tr><td><%=entry.getKey().getName()%></td><td><%=entry.getKey().getHttpUrls()%></td><td><%=httpUrl != null ? httpUrl.getActiveCount() : "N/A"%></td><td><%=httpUrl != null ? httpUrl.getInUseTotal() : "N/A"%></td><td><%=httpUrl != null ? httpUrl.getTotalConnectionsCount() : "N/A"%></td></tr>
		   <%
		}
%>
</table>
			<%
			break;
		default:
			%>
<h4>
<a href="<%=request.getContextPath()%>/admin?HttpServices">HttpServices</a><br><br>
<a href="<%=request.getContextPath()%>/admin?MappedHttpServices">HttpServices with path mapping</a><br><br>
<a href="<%=request.getContextPath()%>/admin?JMSServices">JMSServices</a><br><br>
<a href="<%=request.getContextPath()%>/admin?TimerServices">TimerServices</a><br><br>
<a href="<%=request.getContextPath()%>/admin?InternalServices">InternalServices</a><br><br>
<a href="<%=request.getContextPath()%>/admin?WorkerPools">WorkerPools</a><br><br>
<a href="<%=request.getContextPath()%>/admin?HttpEndpoints">HttpEndpoints</a><br><br>
</h4>
Upload Service-JAR:
<form action="<%=ESBServletContextListener.ADMIN_SERVLET_PATH%>/" enctype="multipart/form-data" method="POST">
	<input type="file" name="file">
	<input type="submit" value="Upload">
</form>
			<%
			pathInfo = "";
			break;
		}
	}
   if (pathInfo != null) {
	   Artifact a = globalContext.getFileSystem().getArtifact(pathInfo);
	   if (a instanceof Directory) {
		   Set<String> artifacts = ((Directory) a).getArtifacts().keySet();
%>
<br>Filesystem directory "<%=a.getURI()%>" (<%=artifacts.size()%> artifacts)
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Name</b></td></tr>
<%
		   for (String name : artifacts) {
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
			   	} else if (a instanceof JarArtifact && a.isValidated()) {
			   %>
					   <tr><td>Used</td><td><%=((JarArtifact) a).isUsed()%></td></tr>
					   </table>
					   <br>
					   <table border="1"><tr bgcolor="#EEEEEE"><td><b>Entries</b></td></tr>
					   <%
					   	   for (String e : ((JarArtifact) a).getEntries()) {
					   %>
					  	<tr><td><%=e%></td></tr>
					   <%
					       }
					   %>
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
		   		java.io.InputStream inputStream = a.getContentAsStream();
		   		String content = new String(com.artofarc.util.IOUtils.copy(inputStream), "UTF-8").replace("&", "&amp;");
		   		inputStream.close();
			%>
				<br>
				<form action="<%=request.getContextPath() + "/" + ESBServletContextListener.ADMIN_SERVLET_PATH + pathInfo%>" enctype="text/plain" method="POST">
					<textarea name="content" rows="50" cols="200" spellcheck="false"<%if (a.getModificationTime() == 0) {%> readonly<%}%>><%=content%></textarea>
					<input type="submit" value="Change">
				</form>
			<%
			}
	   }
   }
%>
</body>
</html>
