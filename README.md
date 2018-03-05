# ESB Zero - A minimal viable service gateway

Features:
- Supports the well known VETRO pattern
- Executes service flows defined in XML
- Supports SOAP, REST and conversion between
- Supports efficient XML Pipelines based on XQJ (XQuery API for Java)
- Supports HTTP, JMS, JDBC (only outbound) and Kafka
- Uses resource- and threadpools for effective resource utilization, thus supporting QoS.
- New service flows and threadpools can configured at runtime without outage
- Simple REST admin services  

### Howto build ###

You need to have [Maven](http://maven.apache.org/) and [Java](http://www.oracle.com/technetwork/java/javase/downloads/index.html) installed.

ESB Zero has been tested with Maven 3.5.2 and JDK7-u80.

Java 7 should be used for building in order to support both Java 7 and Java 8 at runtime.

mvn package will build a WAR file "esb0.war"

### Howto deploy ###

ESB Zero requires a Java Servlet Container conforming to servlet 3.0 API. 
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
1) Either you use a maven import wizard
2) Or create something like a "Dynamic Web Project"
