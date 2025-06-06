<%@page import="java.util.*"%>
<%@page import="com.artofarc.esb.*"%>
<%@page import="com.artofarc.esb.context.GlobalContext"%>
<%@page import="com.artofarc.esb.servlet.ESBServletContextListener"%>
<%@page import="com.artofarc.esb.servlet.HttpConsumer"%>
<%@page import="com.artofarc.esb.jms.JMSConsumer"%>
<%@page import="com.artofarc.esb.context.WorkerPool"%>
<%@page import="com.artofarc.esb.http.HttpUrlSelector"%>
<%@page import="com.artofarc.esb.resource.LRUCacheWithExpirationFactory"%>
<%@page import="com.artofarc.esb.artifact.*"%>
<%@page import="com.artofarc.util.DataStructures"%>
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
	String adminPath = request.getContextPath() + ESBServletContextListener.ADMIN_SERVLET_PATH;
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
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Path</b></td><td><b>Uri</b></td><td><b>PoolSize</b></td><td><b>Completed tasks</b></td><td><b>Execution time</b></td><td><b>Enabled</b></td><td><b>Delete</b></td></tr>
<%
			for (String path : DataStructures.asSortedList(globalContext.getHttpServicePaths())) {
				HttpConsumer consumerPort = globalContext.getHttpService(path);
				%>
				<tr>
					<td><%=path%></td><td><a href="<%=request.getContextPath() + request.getServletPath() + consumerPort.getUri()%>"><%=consumerPort.getUri()%></a></td>
					<td><%=consumerPort.getPoolSize()%>/<%=consumerPort.getMaxPoolSize()%></td>
					<td><%=consumerPort.getCompletedTaskCount()%></td>
					<td><%=consumerPort.getExecutionTime()%></td>
					<td><form method="post" action="<%=adminPath + consumerPort.getUri()%>?HttpServices"><input type="submit" value="<%=consumerPort.isEnabled()%>"/></form></td>
					<td><form action="<%=adminPath + consumerPort.getUri()%>" onsubmit="return confirm('Are you sure to delete \'<%=consumerPort.getUri()%>\'?');"><input type="submit" value="delete"/><input type="hidden" name="DELETE" value="HttpServices"/></form></td>
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
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Path</b></td><td><b>Uri</b></td><td><b>PoolSize</b></td><td><b>Completed tasks</b></td><td><b>Execution time</b></td><td><b>Enabled</b></td><td><b>Delete</b></td></tr>
<%
			for (String path : globalContext.getMappedHttpServicePaths()) {
				HttpConsumer consumerPort = globalContext.getHttpService(path);
				%>
				<tr>
					<td><%=path%>*</td><td><a href="<%=request.getContextPath() + request.getServletPath() + consumerPort.getUri()%>"><%=consumerPort.getUri()%></a></td>
					<td><%=consumerPort.getPoolSize()%>/<%=consumerPort.getMaxPoolSize()%></td>
					<td><%=consumerPort.getCompletedTaskCount()%></td>
					<td><%=consumerPort.getExecutionTime()%></td>
					<td><form method="post" action="<%=adminPath + consumerPort.getUri()%>?MappedHttpServices"><input type="submit" value="<%=consumerPort.isEnabled()%>"/></form></td>
					<td><form action="<%=adminPath + consumerPort.getUri()%>" onsubmit="return confirm('Are you sure to delete \'<%=consumerPort.getUri()%>\'?');"><input type="submit" value="delete"/><input type="hidden" name="DELETE" value="MappedHttpServices"/></form></td>
				</tr>
				<%
			}
%>
</table>
			<%
			break;
		case "JMSServices":
			Map<JMSConsumer, Boolean> serviceStates = new TreeMap<>((c1, c2) -> c1.getKey().compareTo(c2.getKey()));
			int enabledCount = 0;
			for (JMSConsumer jmsConsumer : globalContext.getJMSConsumers()) {
				boolean enabled = jmsConsumer.isEnabled();
				serviceStates.put(jmsConsumer, enabled);
				if (enabled) ++enabledCount; 
			}
			%>
<br>JMSServices (total=<%=serviceStates.size()%>, enabled=<%=enabledCount%>, disabled=<%=serviceStates.size()-enabledCount%>):
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Key</b></td><td><b>Uri</b></td><td><b>Worker count</b></td><td><b>Completed tasks</b></td><td><b>Execution time</b></td><td><b>Current sent/receive delay</b></td><td><b>LastChangeOfState</b></td><td><b>Enabled</b></td><td><b>Delete</b></td></tr>
<%
			for (Map.Entry<JMSConsumer, Boolean> serviceState : serviceStates.entrySet()) {
				JMSConsumer jmsConsumer = serviceState.getKey();
				%>
				<tr>
					<td><%=jmsConsumer.getKey()%></td>
					<td><a href="<%=request.getContextPath() + request.getServletPath() + jmsConsumer.getUri()%>"><%=jmsConsumer.getUri()%></a></td>
					<td><%=jmsConsumer.getWorkerCount()%>/<%=jmsConsumer.getMaxWorkerCount()%></td>
					<td><%=jmsConsumer.getCompletedTaskCount()%></td>
					<td><%=jmsConsumer.getExecutionTime()%></td>
					<td><%=jmsConsumer.getCurrentSentReceiveDelay()%></td>
					<td><%=jmsConsumer.getLastChangeOfState()%></td>
					<td>
						<form method="post" action="<%=adminPath + jmsConsumer.getUri()%>?JMSServices">
							<input type="submit" value="<%=serviceState.getValue()%>"/>
							<input type="hidden" name="key" value="<%=jmsConsumer.getKey()%>"/>
							<input type="hidden" name="enable" value="<%=!serviceState.getValue()%>"/>
						</form>
					</td>
					<td><form action="<%=adminPath + jmsConsumer.getUri()%>" onsubmit="return confirm('Are you sure to delete \'<%=jmsConsumer.getUri()%>\'?');"><input type="submit" value="delete"/><input type="hidden" name="DELETE" value="JMSServices"/></form></td>
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
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Delay</b></td><td><b>Uri</b></td><td><b>Completed tasks</b></td><td><b>Enabled</b></td><td><b>Delete</b></td></tr>
<%
			for (TimerService consumerPort : globalContext.getTimerServices()) {
				%>
				<tr>
					<td><%=consumerPort.getDelay() != null ? consumerPort.getDelay() + " " + consumerPort.getTimeUnit().name().toLowerCase(Locale.ROOT) : "N/A"%></td>
					<td><a href="<%=request.getContextPath() + request.getServletPath() + consumerPort.getUri()%>"><%=consumerPort.getUri()%></a></td>
					<td><%=consumerPort.getCompletedTaskCount()%></td>
					<td><form method="post" action="<%=adminPath + consumerPort.getUri()%>?TimerServices"><input type="submit" value="<%=consumerPort.isEnabled()%>"/></form></td>
					<td><form action="<%=adminPath + consumerPort.getUri()%>" onsubmit="return confirm('Are you sure to delete \'<%=consumerPort.getUri()%>\'?');"><input type="submit" value="delete"/><input type="hidden" name="DELETE" value="TimerServices"/></form></td>
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
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Dirs</b></td><td><b>Uri</b></td><td><b>Completed tasks</b></td><td><b>Enabled</b></td><td><b>Delete</b></td></tr>
<%
		for (FileWatchEventConsumer consumerPort : globalContext.getFileWatchEventConsumers()) {
			%>
			<tr>
				<td><%=consumerPort.getDirs()%></td>
				<td><a href="<%=request.getContextPath() + request.getServletPath() + consumerPort.getUri()%>"><%=consumerPort.getUri()%></a></td>
				<td><%=consumerPort.getCompletedTaskCount()%></td>
				<td><form method="post" action="<%=adminPath + consumerPort.getUri()%>?FileWatchEventConsumer"><input type="submit" value="<%=consumerPort.isEnabled()%>"/></form></td>
				<td><form action="<%=adminPath + consumerPort.getUri()%>" onsubmit="return confirm('Are you sure to delete \'<%=consumerPort.getUri()%>\'?');"><input type="submit" value="delete"/><input type="hidden" name="DELETE" value="FileWatchEventConsumer"/></form></td>
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
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Topics</b></td><td><b>Config</b></td><td><b>Uri</b></td><td><b>Completed tasks</b></td><td><b>Enabled</b></td><td><b>Delete</b></td></tr>
<%
		for (KafkaConsumerPort consumerPort : globalContext.getKafkaConsumers()) {
			%>
			<tr>
				<td><%=consumerPort.getTopics()%></td>
				<td><%=consumerPort.getConfig()%></td>
				<td><a href="<%=request.getContextPath() + request.getServletPath() + consumerPort.getUri()%>"><%=consumerPort.getUri()%></a></td>
				<td><%=consumerPort.getCompletedTaskCount()%></td>
				<td><form method="post" action="<%=adminPath + consumerPort.getUri()%>?KafkaConsumerServices"><input type="submit" value="<%=consumerPort.isEnabled()%>"/></form></td>
				<td><form action="<%=adminPath + consumerPort.getUri()%>" onsubmit="return confirm('Are you sure to delete \'<%=consumerPort.getUri()%>\'?');"><input type="submit" value="delete"/><input type="hidden" name="DELETE" value="KafkaConsumerServices"/></form></td>
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
					<td><form action="<%=adminPath + consumerPort.getUri()%>" onsubmit="return confirm('Are you sure to delete \'<%=consumerPort.getUri()%>\'?');"><input type="submit" value="delete"/><input type="hidden" name="DELETE" value="InternalServices"/></form></td>
				</tr>
				<%
			}
%>
</table>
			<%
			break;
		case "LocalJNDIobjects":
			%>
<br>Local JNDI objects:
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Name</b></td><td><b>Type</b></td><td><b>Active Connections</b></td><td><b>Uri</b></td><td><b>Delete</b></td><td><b>Action</b></td></tr>
<%
			for (String propertyName : globalContext.getCachedProperties()) {
				Object object = globalContext.getProperty(propertyName);
				String uri = globalContext.getArtifactUri(object);
				Object activeConnections = "N/A";
				if (object instanceof javax.sql.DataSource) {
					try {
						activeConnections = com.artofarc.util.ReflectionUtils.eval(object, "hikariPoolMXBean.activeConnections");
					} catch (Exception e) {
						// ignore
					}
				} else if (uri == null) {
					continue;
				}
				%>
				<tr>
					<td><%=propertyName%></td>
					<td><%=object.getClass().getName()%></td>
					<td><%=activeConnections%></td>
				<%
				if (uri != null) {
					%>
					<td><a href="<%=request.getContextPath() + request.getServletPath() + uri%>"><%=uri%></a></td>
					<td><form action="<%=adminPath + uri%>" onsubmit="return confirm('Are you sure to delete \'<%=uri%>\'?');"><input type="submit" value="delete"/><input type="hidden" name="DELETE" value="DataSources"/></form></td>
					<%
					JNDIObjectFactoryArtifact a = globalContext.getFileSystem().getArtifact(uri);
					if (a.getAdminPostAction() != null) {
						%>
						<td><form method="post" action="<%=adminPath + uri%>?LocalJNDIobjects"><input type="submit" value="<%=a.getAdminPostAction()%>"/></form></td>
						<%
					} else {
						%>
						<td></td>
						<%
					}
				} else {
					%>
					<td></td><td></td><td></td>
					<%
				}
				%>
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
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Uri</b></td><td><b>Poolsize</b></td><td><b>Size of work queue</b></td><td><b>Active Threads</b></td><td><b>Completed tasks</b></td></tr>
<%
			for (WorkerPool workerPool : globalContext.getWorkerPools()) {
				%>
				<tr>
					<td><a href="<%=workerPool.getName() != "default" ? request.getContextPath() + request.getServletPath() + workerPool.getName() + "." + WorkerPoolArtifact.FILE_EXTENSION : ""%>"><%=workerPool.getName()%></a></td>
					<td><%=workerPool.getPoolSize()%></td>
					<td><%=workerPool.getQueueSize()%></td>
					<td><%=workerPool.getActiveCount()%></td>
					<td><%=workerPool.getCompletedTaskCount()%></td>
				</tr>
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
			for (Map.Entry<String, HttpUrlSelector> entry : globalContext.getHttpEndpointRegistry().getHttpUrlSelectors().entrySet()) {
				HttpUrlSelector httpUrl = entry.getValue();
				%>
				<tr><td><%=entry.getKey()%></td><td><%=httpUrl != null ? httpUrl.getFirstHttpEndpoint().getHttpUrls() : "N/A"%></td><td><%=httpUrl != null ? httpUrl.getActiveCount() : "N/A"%></td><td><%=httpUrl != null ? httpUrl.getInUseTotal() : "N/A"%></td><td><%=httpUrl != null ? httpUrl.getTotalConnectionsCount() : "N/A"%></td></tr>
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
		case "Cookies":
			%>
<br>Cookies:
<table border="1"><tr bgcolor="#EEEEEE"><td><b>Domain</b></td><td><b>Path</b></td><td><b>Name</b></td><td><b>Value</b></td><td><b>HTTP only</b></td><td><b>Max age</b></td></tr>
<%
			java.net.CookieManager cookieManager = globalContext.getHttpGlobalContext().getCookieManager();
			if (cookieManager != null) {
				for (java.net.HttpCookie httpCookie : cookieManager.getCookieStore().getCookies()) {
					%>
					<tr><td><%=httpCookie.getDomain()%></td><td><%=httpCookie.getPath()%></td><td><%=httpCookie.getName()%></td><td><%=httpCookie.getValue()%></td><td><%=httpCookie.isHttpOnly()%></td><td><%=httpCookie.getMaxAge()%></td></tr>
					<%
				}
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
	</tr>
	<tr bgcolor="#EEEEEE">
		<td><a href="<%=request.getContextPath()%>/admin?TimerServices">TimerServices</a></td>
		<td><a href="<%=request.getContextPath()%>/admin?FileWatchEventConsumer">FileWatchEventConsumer</a></td>
		<td><a href="<%=request.getContextPath()%>/admin?KafkaConsumerServices">KafkaConsumerServices</a></td>
	</tr>
	<tr bgcolor="#EEEEEE">
		<td><a href="<%=request.getContextPath()%>/admin?InternalServices">InternalServices</a></td>
		<td><a href="<%=request.getContextPath()%>/admin?WorkerPools">WorkerPools</a></td>
		<td><a href="<%=request.getContextPath()%>/admin?LocalJNDIobjects">Local JNDI objects</a></td>
	</tr>
	<tr bgcolor="#EEEEEE">
		<td><a href="<%=request.getContextPath()%>/admin?HttpEndpoints">HttpEndpoints</a></td>
		<td><a href="<%=request.getContextPath()%>/admin?Caches">Caches</a></td>
		<td><a href="<%=request.getContextPath()%>/admin?Cookies">Cookies</a></td>
	</tr>
</table>
<br>
Upload Service-JAR:
<form action="<%=adminPath%>/" enctype="multipart/form-data" method="POST">
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
			List<String> artifacts = DataStructures.asSortedList(((Directory) a).getArtifacts().keySet());
%>
<br>Filesystem directory {<%=application.getAttribute(ESBServletContextListener.ESB_ROOT_DIR)%>}<%=a.getURI()%> (<%=artifacts.size()%> artifacts)
<%
			if (!a.getURI().isEmpty()) {
%>

<form action="<%=adminPath + a.getURI()%>" enctype="multipart/form-data" method="POST">
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
				<tr><td>Modified</td><td><%=new Date(a.getModificationTime())%></td></tr>
				<%
			if (a instanceof SchemaArtifact) {
				%>
				<tr><td>Namespace</td><td><%=((SchemaArtifact) a).getNamespace()%></td></tr>
				<%
			} else if (a instanceof JarArtifact && a.isValidated()) {
				JarArtifact jarArtifact = (JarArtifact) a;
				%>
				<tr><td>Used</td><td><%=jarArtifact.isUsed()%></td></tr>
				<%
				if (jarArtifact.isUsed()) {
					%>
					</table>
					<br>
					<table border="1"><tr bgcolor="#EEEEEE"><td><b>Entry</b></td><td><b>Loaded</b></td></tr>
					<%
					for (Map.Entry<String, JarArtifact.Jar.Entry> entry : jarArtifact.getEntries().entrySet()) {
						%>
						<tr><td><%=entry.getKey()%></td><td><%=entry.getValue().isConsumed()%></td></tr>
						<%
					}
				}
			}
			%>
			</table>
			<br>
			<table border="1"><tr bgcolor="#EEEEEE"><td><b>Referenced</b></td></tr>
			<%
			for (String r : DataStructures.asSortedList(a.getReferenced())) {
				%>
				<tr><td><a href="<%=request.getContextPath() + request.getServletPath() + r%>"><%=r%></a></td></tr>
				<%
			}
			%>
			</table>
			<br>
			<table border="1"><tr bgcolor="#EEEEEE"><td><b>ReferencedBy</b></td></tr>
			<%
			for (String r : DataStructures.asSortedList(a.getReferencedBy())) {
				%>
				<tr><td><a href="<%=request.getContextPath() + request.getServletPath() + r%>"><%=r%></a></td></tr>
				<%
			}
			%>
			</table>
			<%
			if (a.getContentType().startsWith("text/")) {
				String content;
				try (java.io.InputStream inputStream = a.getContentAsStream()) {
					content = com.artofarc.util.IOUtils.toString(inputStream, a.getEncoding()).replace("&", "&amp;");
				}
				%>
				<br>
				<form action="<%=adminPath + pathInfo%>" method="POST" accept-charset="utf-8">
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
