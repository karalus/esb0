# esb0
ESB Zero - A minimal viable service gateway


### Howto build ###

You need to have [Maven ](http://maven.apache.org/) and [Java](http://www.oracle.com/technetwork/java/javase/downloads/index.html) installed.

ESB Zero has been tested with Maven 3.5.2 and JDK7-u80.

Java 7 should be used for building in order to support both Java 7 and Java 8 at runtime.

mvn package will build a WAR file "esb0.war"

### Howto deploy ###

ESB Zero requires a Java Servlet Container conforming to servlet 3.0 API.

ESB Zero has been tested with Tomcat 7, 8 and 8.5, Wildfly and Jetty 9.0.0. 

### Working with the sources ###

The GIT repository contains Eclipse project files for working with Eclipse 3.7.2.

Any other IDE will also work since there is nothing special about it.
