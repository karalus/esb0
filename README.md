# ESB Zero - A minimal viable service gateway

Features:
- Supports the well known VETRO pattern
- Executes service flows defined in XML
- Supports SOAP, JSON/REST and conversion between each
- Supports efficient XML Pipelines based on XQJ (XQuery API for Java)
- Supports HTTP, JMS, JDBC (only outbound) and Kafka
- Uses resource- and threadpools for effective resource utilization, thus supporting QoS.
- New service flows and threadpools can be configured at runtime without outage
- Is performant like a network component. Can act at the speed of a reverse proxy (only few millis penalty).
- Simple REST admin services

### Design goals ###

Most ESB products are very heavy suites which is counterproductive for the main purpose - to act as a fast and easy service gateway to control and monitor service usage within your enterprise.
ESB Zero is designed to be very small and thus manageable. The zipped sources are currently about 100k of size!
The minimal set of features is supported based on the VETRO pattern.
It is not meant to be used for complex EAI scenarios. For this end better use another tool.

Why not using Apache Camel?
Apache Camel is a great project, but with offering > 200 Components and all of the EAI patterns it is already to huge for the above mentioned purpose.

Why not using Karaf for deploying?
OSGI is a great idea when managing different versions of JARs but unnecessarily complex for not code based artifacts.

Why not using Apache CXF?
For a generic gateway which is not really processing the data content this is not needed.

How to achieve good performance and having small memory footprint when processing XML?
XML pipelining is an efficient means because it does not require to have a full DOM in memory but can rather do streaming in certain scenarios. Refer to e.g. https://www.progress.com/tutorials/xquery/api-for-java-xqj

Currently ESB Zero uses well proven [Saxon] (http://saxon.sourceforge.net/) but can be run with any [XQJ] (http://xqj.net/) compliant product.

### How to build ###

You need to have [Maven](http://maven.apache.org/) and [Java](http://www.oracle.com/technetwork/java/javase/downloads/index.html) installed.

ESB Zero has been tested with Maven 3.5.2 and JDK7-u80.

Java 7 should be used for building in order to support both Java 7 and Java 8 at runtime.

"mvn package" will build a WAR file "esb0.war"

### How to deploy ###

ESB Zero requires a Java Servlet Container conforming to the servlet 3.0 API. 
It has been tested with Tomcat 7, 8 and 8.5, Wildfly and Jetty 9.0.0. 

ESB Zero needs one directory to retrieve and persist service flows and other artifacts.
Per default it is expected to have a folder named "esb_root" in the user home folder (of the user running the servlet container).
Either create an empty directory with name "esb_root" there or set environment variable ESB_ROOT_DIR to an existing folder of your choice.

Note: This folder can be empty but it must exist!

If you are using JMS or JDBC actions within your flows you'll need to configure JMS-Providers and/or Datasources in your Servlet Container. These resources will be used by JNDI lookup.

Deploy it in the servlet container.

### Working with the sources ###

The GIT repository contains Eclipse project files for working with Eclipse 3.7.2.

Any other IDE will also do since there is nothing special about it:
1) Either you use a maven project import wizard
2) Or create something like a "Dynamic Web Project"
