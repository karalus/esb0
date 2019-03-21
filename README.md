# ESB Zero - A minimal viable service gateway

Features:
- Supports the well known VETRO pattern
- Executes service flows defined in XML which can be crafted in an XML editor or could be generated by a service repository
- Supports SOAP, JSON/REST and **generic** conversion between each (just include a XSD and everything is done for you, no need to build the correct XML manually)
- Supports efficient XML Pipelines based on XQJ (XQuery API for Java)
- Service flows can make use of XPath 2.0, XQuery 3.1 and XSLT 3.0 interchangeably within one streaming pipeline
- For accessing data inside of JSON we use [JSON Pointer](https://tools.ietf.org/html/rfc6901) (Message transformation with JSON data can be done with XSLT or XQuery)
- Java code can be invoked dynamically if necessary e.g. for special transformations
- Supports HTTP, JMS, JDBC, Files and [Kafka](https://kafka.apache.org/)
- Can map between synchronous and asynchronous messages exchange patterns
- Supports GZIP and [Fast Infoset](https://en.wikipedia.org/wiki/Fast_Infoset) encoding/decoding and (partially) MTOM/XOP
- Uses resource- and threadpools for effective resource utilization, thus supporting QoS per service.
- Includes a HTTP loadbalancer component with health check for outbound HTTP traffic
- New service flows and threadpools can be configured at runtime without service outage
- Is performant like a network component. Can act at the speed of a reverse proxy (only few millis penalty) even when processing XML.
- Outperforms most other Service Gateways when XML processing is needed.
- Simple REST admin services to deploy and control service flows
- JMX support, i.e. MBeans for remote monitoring & management

### Fitness for production ###

The version 1.2 is currently running at one of our customers site in production since December 2018 processing millions of business transactions a day. XML messages are up to 20Mb of size. No unplanned outages and overall only 3s of major GC time spent per month (the former commercial ESB product had a 16s major GC every 5min and needed to be restarted every night).

### Design goals ###

Most ESB products are very heavy suites which is counterproductive for their main purpose - to act as a fast and simple service gateway to control and monitor service usage within your enterprise.

ESB Zero is designed to be very small and thus manageable. The zipped (7zip) sources are currently about 64k of size!

The minimal set of features is supported based on the VETRO pattern. All basic EAI components that are needed are built-in with the exemption of stateful components (e.g. [resquencer](https://www.enterpriseintegrationpatterns.com/patterns/messaging/Resequencer.html)). As the ESB Zero does not maintain state on its own, it can be easily scaled up and runs very stable.

Having no built-in state does not necessarily mean that you need more tools or products. You can build many patterns with ESB Zero and a MOM and/or DB. For instance the mentioned resequencer can be realised with two ESB Zero service flows and a DB table in the middle (First flow puts messages into the table along with sequence nr and timestamp and the second flow polls the table for messages arrived before a given period of time in the correct order and forwards the messages to the next endpoint).
This general concept puts the burden of persistent state (not losing or corrupting it) on the MOM/DB.

ESB Zero is not meant to be used for complex EAI scenarios (Orchestration, COTS Components, ETL, complex logic, ...). For this end better use another tool.

### FAQ ###

Why not using Apache Camel?

Apache Camel is a great project, but with offering > 200 Components and all known EAI patterns it is already too huge for the above mentioned purpose.

Why not using Apache Karaf for deploying?

OSGi is a great idea when managing different versions of JARs but unnecessarily complex for not code based artifacts.

Why not using Apache CXF?

For a generic gateway which is not really processing the data content this is not needed.

How to achieve good performance and having small memory footprint when processing XML?

XML pipelining is an efficient means because it does not require to have a full DOM in memory but can rather do streaming in certain scenarios. Refer to e.g. https://www.progress.com/tutorials/xquery/api-for-java-xqj

Currently ESB Zero uses well proven [Saxon](http://saxon.sourceforge.net/) but basically could be run with any [XQJ](http://xqj.net/) compliant product.

Will this technology be superseded by Spring Boot plus Camel (new JBoss Fusion concept) running inside of a Docker container?

Using Camel with Spring Boot will run you a few EAI services well, but is not suitable for a scenario where >100 services need to be mediated while only needing endpoint virtualization and validation.
Besides, the memory footprint of ESB Zero running webservices inside docker is even less than Spring Boot plus Camel!

Will this technology be superseded by Istio?

Yes, when all of the services in your enterprise are micro services talking natively REST/JSON! No monoliths, no legacy, no 3rd party stuff. I.e. that means a no for the medium future for most of us. While I'm a big fan of [Kubernetes](https://kubernetes.io/) I don't expect that everything will be µServices soon.
Even in a Kubernetes environment where endpoint virtualization and load balancing is done by K8s Controller, ESB Zero can be used (also as a sidecar) to provide for message transformation/validation (e.g JSON/XML) and protocol conversion (e.g. HTTP/JMS).

### How to build ###

You need to have [Maven](http://maven.apache.org/) and [Java](http://www.oracle.com/technetwork/java/javase/downloads/index.html) installed.

ESB Zero build has been tested with Maven 3.5.2 plus JDK7-u80 and JDK8.

Java 7 should be used for building in order to support both Java 7 and Java 8 at runtime.

"mvn package" will build a WAR file "esb0.war"

### How to deploy ###

ESB Zero requires a Java Servlet Container conforming to the servlet 3.0 API. 
It has been tested with Tomcat 7, 8 and 8.5, Wildfly, Jetty 9.0.0. and JBoss EAP 7.1.

ESB Zero needs one directory to retrieve service flows and other artifacts from and persist to.
Per default it is expected to have a folder named "esb_root" in the user home folder (of the user running the servlet container).
Either create an empty directory with name "esb_root" there or set environment variable ESB_ROOT_DIR to an existing folder of your choice.

Note: This folder can be empty but it must exist!

If you are using JMS or JDBC actions within your flows you'll need to configure JMS-Providers and/or Datasources in your Servlet Container. These resources will be used by JNDI lookup.

Deploy it in your servlet container of choice.

Test if the admin UI is accessible: http://localhost:8080/esb0/admin

### Working with the sources ###

The GIT repository contains old Eclipse project files for working with Eclipse 3.7.2.

Any other IDE will also do since there is nothing special about it:
1) Either you use a maven project import wizard
2) Or create something like a "Dynamic Web Project"

### On which projects/technology does ESB Zero depend on? ###
There are only very few dependencies:

It is written in Java 7 and implements a servlet based on 3.0.1 API.

- For WSDL parsing [WSDL4J](https://sourceforge.net/projects/wsdl4j/) is used.
- For XML processing we use the XQJ implementation in [Saxon-HE](https://sourceforge.net/projects/saxon/files/Saxon-HE/9.8/)
- For conversion between XML and JSON [MOXy](http://www.eclipse.org/eclipselink/documentation/2.6/moxy/json002.htm) is used.
- FastInfoset support is implemented using "com.sun.xml.fastinfoset".
- Logging is done with [SLF4J](https://www.slf4j.org/). Optionally combined with [Logback](https://logback.qos.ch/) it gives the best performance.

Optional
- For using Kafka you need the [Kafka Java Client](https://cwiki.apache.org/confluence/display/KAFKA/Clients)
- For using ActiveMQ as JMS provider you need to include it into your Servlet Container
