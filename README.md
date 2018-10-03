# ESB Zero - A minimal viable service gateway

Features:
- Supports the well known VETRO pattern
- Executes service flows defined in XML
- Supports SOAP, JSON/REST and conversion between each
- Supports efficient XML Pipelines based on XQJ (XQuery API for Java)
- Supports HTTP, JMS, JDBC, Files and [Kafka](https://kafka.apache.org/)
- Supports GZIP and [Fast Infoset](https://en.wikipedia.org/wiki/Fast_Infoset) encoding/decoding and (partially) MTOM/XOP
- Uses resource- and threadpools for effective resource utilization, thus supporting QoS per service.
- Includes a HTTP loadbalancer component with health check for outbound HTTP traffic
- New service flows and threadpools can be configured at runtime without service outage
- Is performant like a network component. Can act at the speed of a reverse proxy (only few millis penalty) even when processing XML.
- Outperforms most other Service Gateways when XML processing is needed.
- Simple REST admin services to deploy and control service flows

### Design goals ###

Most ESB products are very heavy suites which is counterproductive for the main purpose - to act as a fast and simple service gateway to control and monitor service usage within your enterprise.

ESB Zero is designed to be very small and thus manageable. The zipped (7zip) sources are currently about 50k of size!

The minimal set of features is supported based on the VETRO pattern.

It is not meant to be used for complex EAI scenarios. For this end better use another tool.

Why not using Apache Camel?

Apache Camel is a great project, but with offering > 200 Components and all known EAI patterns it is already too huge for the above mentioned purpose.

Why not using Apache Karaf for deploying?

OSGi is a great idea when managing different versions of JARs but unnecessarily complex for not code based artifacts.

Why not using Apache CXF?

For a generic gateway which is not really processing the data content this is not needed.

How to achieve good performance and having small memory footprint when processing XML?

XML pipelining is an efficient means because it does not require to have a full DOM in memory but can rather do streaming in certain scenarios. Refer to e.g. https://www.progress.com/tutorials/xquery/api-for-java-xqj

Currently ESB Zero uses well proven [Saxon](http://saxon.sourceforge.net/) but basically could be run with any [XQJ](http://xqj.net/) compliant product.

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

Deploy it in your servlet container of choice.

Test if the admin UI is accessible: http://localhost:8080/esb0/admin

### Working with the sources ###

The GIT repository contains Eclipse project files for working with Eclipse 3.7.2.

Any other IDE will also do since there is nothing special about it:
1) Either you use a maven project import wizard
2) Or create something like a "Dynamic Web Project"

### On which projects/technology does ESB Zero depend on? ###
There are only very few dependencies:

It is written in Java 7 and implements a servlet based on 3.0.1 API.

- For WSDL parsing [WSDL4J](https://sourceforge.net/projects/wsdl4j/) is used.
- For XML processing we use the XQJ implementation in [Saxon-HE](https://sourceforge.net/projects/saxon/files/Saxon-HE/9.8/)
- For conversion between XML and JSON [MOXy](http://www.eclipse.org/eclipselink/documentation/2.5/moxy/json002.htm) is used.
- FastInfoset support is implemented using "com.sun.xml.fastinfoset".

Optional
- For using Kafka you need the [Kafka Java Client](https://cwiki.apache.org/confluence/display/KAFKA/Clients)
- For using ActiveMQ as JMS provider you need to include it into your Servlet Container
