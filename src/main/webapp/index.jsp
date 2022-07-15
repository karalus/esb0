<%@page import="java.util.*"%>
<%@page import="com.artofarc.esb.*"%>
<%@page import="com.artofarc.esb.context.GlobalContext"%>
<%@page import="com.artofarc.esb.servlet.ESBServletContextListener"%>
<%@page import="com.artofarc.esb.servlet.HttpConsumer"%>
<%@page import="com.artofarc.esb.jms.JMSConsumer"%>
<%@page import="com.artofarc.esb.context.WorkerPool"%>
<%@page import="com.artofarc.esb.http.HttpEndpoint"%>
<%@page import="com.artofarc.esb.http.HttpUrlSelector"%>
<%@page import="com.artofarc.esb.resource.LRUCacheWithExpirationFactory"%>
<%@page import="com.artofarc.esb.artifact.*"%>
<%@page import="com.artofarc.util.ReflectionUtils"%>
<html>
<head>
<style>
input[type="submit"][value="true"] {
	color: white;
	background-color: green;
}
input[type="submit"][value="false"] {
	color: white;
	background-color: red;
}
.main * td {
	padding: 10;
}
</style>
</head>
<body style="background-color:<%=System.getProperty("esb0.adminUI.bgcolor", "transparent")%>">
<%
	GlobalContext globalContext = (GlobalContext) application.getAttribute(ESBServletContextListener.CONTEXT);
%>
<h2>ESB Zero - A lightweight service gateway (Version <%=globalContext.getVersion()%> build time <%=globalContext.getProperty(GlobalContext.BUILD_TIME)%>)</h2>
<%
	String pathInfo = request.getPathInfo();
	if (pathInfo == null) {
		String banner = System.getProperty("esb0.adminUI.banner");
		if (banner != null) {
%>
	<h3><%=globalContext.bindProperties(banner)%></h3>
<%
		} else if (!FileSystem.environment.equals("default")) {
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
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Key</b></td><td><b>Uri</b></td><td><b>WorkerCount</b></td><td><b>Completed tasks</b></td><td><b>Current SentReceiveDelay</b></td><td><b>Current retries</b></td><td><b>LastChangeOfState</b></td><td><b>Enabled</b></td><td><b>Delete</b></td></tr> 
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
		    <td><%=jmsConsumer.getCurrentSentReceiveDelay()%></td>
		    <td><%=jmsConsumer.getRetries()%></td>
		    <td><%=jmsConsumer.getLastChangeOfState()%></td>
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
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Uri</b></td><td><b>Delay</b></td><td><b>Completed tasks</b></td><td><b>Enabled</b></td><td><b>Delete</b></td></tr> 
<%
		for (TimerService consumerPort : globalContext.getTimerServices()) {
		   %>
		   <tr>
		    <td><a href="<%=request.getContextPath() + request.getServletPath() + consumerPort.getUri()%>"><%=consumerPort.getUri()%></a></td>
		    <td><%=consumerPort.getDelay() != null ? consumerPort.getDelay() + " " + consumerPort.getTimeUnit().name().toLowerCase(Locale.ROOT) : "N/A"%></td>
		    <td><%=consumerPort.getCompletedTaskCount()%></td>
		    <td><form method="post" action="<%=ESBServletContextListener.ADMIN_SERVLET_PATH%><%=consumerPort.getUri()%>?TimerServices"><input type="submit" value="<%=consumerPort.isEnabled()%>"/></form></td>
		    <td><form action="<%=ESBServletContextListener.ADMIN_SERVLET_PATH%><%=consumerPort.getUri()%>" onsubmit="return confirm('Are you sure to delete \'<%=consumerPort.getUri()%>\'?');"><input type="submit" value="delete"/><input type="hidden" name="DELETE" value="TimerServices"/></form></td>
		   </tr>
		   <%
		}
%>
</table>
			<%
			break;
		case "FileWatchEventConsumer":
			%>
<br>FileWatchEventConsumer:
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Uri</b></td><td><b>Dirs</b></td><td><b>Completed tasks</b></td><td><b>Enabled</b></td><td><b>Delete</b></td></tr> 
<%
 	for (FileWatchEventConsumer consumerPort : globalContext.getFileWatchEventConsumers()) {
    %>
		   <tr>
		    <td><a href="<%=request.getContextPath() + request.getServletPath() + consumerPort.getUri()%>"><%=consumerPort.getUri()%></a></td>
		    <td><%=consumerPort.getDirs()%></td>
		    <td><%=consumerPort.getCompletedTaskCount()%></td>
		    <td><form method="post" action="<%=ESBServletContextListener.ADMIN_SERVLET_PATH%><%=consumerPort.getUri()%>?FileWatchEventConsumer"><input type="submit" value="<%=consumerPort.isEnabled()%>"/></form></td>
		    <td><form action="<%=ESBServletContextListener.ADMIN_SERVLET_PATH%><%=consumerPort.getUri()%>" onsubmit="return confirm('Are you sure to delete \'<%=consumerPort.getUri()%>\'?');"><input type="submit" value="delete"/><input type="hidden" name="DELETE" value="TimerServices"/></form></td>
		   </tr>
		   <%
		}
%>
</table>
			<%
			break;
		case "KafkaConsumerServices":
			%>
<br>KafkaConsumerServices:
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Uri</b></td><td><b>Config</b></td><td><b>Topics</b></td><td><b>Completed tasks</b></td><td><b>Enabled</b></td><td><b>Delete</b></td></tr> 
<%
 	for (KafkaConsumerPort consumerPort : globalContext.getKafkaConsumers()) {
    %>
		   <tr>
		    <td><a href="<%=request.getContextPath() + request.getServletPath() + consumerPort.getUri()%>"><%=consumerPort.getUri()%></a></td>
		    <td><%=consumerPort.getConfig()%></td>
		    <td><%=consumerPort.getTopics()%></td>
		    <td><%=consumerPort.getCompletedTaskCount()%></td>
		    <td><form method="post" action="<%=ESBServletContextListener.ADMIN_SERVLET_PATH%><%=consumerPort.getUri()%>?KafkaConsumerServices"><input type="submit" value="<%=consumerPort.isEnabled()%>"/></form></td>
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
		case "DataSources":
			%>
<br>DataSources:
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Name</b></td><td><b>Type</b></td><td><b>Active Connections</b></td></tr> 
<%
		for (String propertyName : globalContext.getCachedProperties()) {
			Object object = globalContext.getProperty(propertyName);
			if (object instanceof javax.sql.DataSource) {
				Object activeConnections = DataSourceArtifact.isDataSource(object) ? ReflectionUtils.eval(object, "hikariPoolMXBean.activeConnections") : "N/A";
			   %>
			   <tr><td><%=propertyName%></td><td><%=object.getClass().getName()%></td><td><%=activeConnections%></td></tr>
			   <%
			}
		}
%>
</table>
			<%
			break;
		case "WorkerPools":
			%>
<br>WorkerPools:
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Uri</b></td><td><b>Poolsize</b></td><td><b>Size of work queue</b></td><td><b>Active Threads</b></td><td><b>Completed tasks</b></td></tr> 
<%
		for (WorkerPool workerPool : globalContext.getWorkerPools()) {
		   %>
		   <tr><td><a href="<%=workerPool.getName() != "default" ? request.getContextPath() + request.getServletPath() + workerPool.getName() + "." + WorkerPoolArtifact.FILE_EXTENSION : ""%>"><%=workerPool.getName()%></a></td><td><%=workerPool.getPoolSize()%></td><td><%=workerPool.getQueueSize()%></td><td><%=workerPool.getActiveCount()%></td><td><%=workerPool.getCompletedTaskCount()%></td></tr>
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
		case "Caches":
			%>
<br>Caches:
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Name</b></td><td><b>Keys with expiration in seconds</b></td></tr> 
<%
		@SuppressWarnings("unchecked")
		LRUCacheWithExpirationFactory<Object, Object[]> factory = globalContext.getResourceFactory(LRUCacheWithExpirationFactory.class);
		for (String cacheName : factory.getResourceDescriptors()) {
			LRUCacheWithExpirationFactory<Object, Object[]>.Cache cache = factory.getResource(cacheName, null);
		   %>
		   <tr><td><%=cacheName%></td><td><%=cache.getExpirations()%></td></tr>
		   <%
		}
%>
</table>
			<%
			break;
		default:
			%>
<table border="1" style="width:960" class="main">
	<tr bgcolor="#EEEEEE">
		<td><a href="<%=request.getContextPath()%>/admin?HttpServices">HttpServices</a></td>
		<td><a href="<%=request.getContextPath()%>/admin?MappedHttpServices">HttpServices with path mapping</a></td>
		<td><a href="<%=request.getContextPath()%>/admin?JMSServices">JMSServices</a></td>
		<td></td>
	</tr>
	<tr bgcolor="#EEEEEE">
		<td><a href="<%=request.getContextPath()%>/admin?TimerServices">TimerServices</a></td>
		<td><a href="<%=request.getContextPath()%>/admin?FileWatchEventConsumer">FileWatchEventConsumer</a></td>
		<td><a href="<%=request.getContextPath()%>/admin?KafkaConsumerServices">KafkaConsumerServices</a></td>
		<td></td>
	</tr>
	<tr bgcolor="#EEEEEE">
		<td><a href="<%=request.getContextPath()%>/admin?InternalServices">InternalServices</a></td>
		<td></td>
		<td></td>
		<td></td>
	</tr>
	<tr bgcolor="#EEEEEE">
		<td><a href="<%=request.getContextPath()%>/admin?WorkerPools">WorkerPools</a></td>
		<td><a href="<%=request.getContextPath()%>/admin?HttpEndpoints">HttpEndpoints</a></td>
		<td><a href="<%=request.getContextPath()%>/admin?DataSources">DataSources</a></td>
		<td><a href="<%=request.getContextPath()%>/admin?Caches">Caches</a></td>
	</tr>
</table>
<br>
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
<%
		   if (!a.getURI().isEmpty()) {
%>

<form action="<%=request.getContextPath() + "/" + ESBServletContextListener.ADMIN_SERVLET_PATH + a.getURI()%>" enctype="multipart/form-data" method="POST">
  	<input type="file" name="file">
	<input type="submit" value="Upload">
</form>
<%
		   }
%>
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
			   	} else if (a instanceof DataSourceArtifact) {
			   %>
		   			<tr><td></td><td><form action="<%=request.getContextPath() + "/" + ESBServletContextListener.ADMIN_SERVLET_PATH + a.getURI()%>" onsubmit="return confirm('Are you sure to delete \'<%=a.getURI()%>\'?');"><input type="submit" value="delete"/><input type="hidden" name="DELETE" value="DataSources"/></form></td></tr>
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
		   		   		String content = com.artofarc.util.IOUtils.toString(inputStream, java.nio.charset.StandardCharsets.UTF_8).replace("&", "&amp;");
		   		   		inputStream.close();
		   %>
				<br>
				<form action="<%=request.getContextPath() + "/" + ESBServletContextListener.ADMIN_SERVLET_PATH + pathInfo%>" enctype="text/plain" method="POST" accept-charset="utf-8">
					<textarea name="content" rows="50" cols="200" spellcheck="false" style="tab-size:4"<%if (a.getModificationTime() == 0) {%> readonly<%}%>><%=content%></textarea>
					<input type="submit" value="Change">
				</form>
			<%
			}
	   }
   }
%>
</body>
</html>
