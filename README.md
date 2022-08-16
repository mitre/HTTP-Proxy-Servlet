Smiley's HTTP Proxy Servlet
===========================

This is an HTTP Proxy (aka gateway) in the form of a Java servlet.  An HTTP proxy is useful for AJAX applications to communicate with web accessible services on hosts other than where the web application is hosted.  It's a _reverse proxy_, and not really a _forwarding proxy_ albeit the template form of the servlet may blur that line.

This is hardly the first proxy, so why did I write it and thus why might you use it?

 * It's simple -- a single source file implementation
 * It's tested -- have confidence it works ![Build Status](https://github.com/mitre/HTTP-Proxy-Servlet/actions/workflows/maven.yml/badge.svg)
 * It's securable -- via Java EE web.xml or via a servlet filter such as [Spring-Security]([http://static.springsource.org/spring-security/site/)
 * It's extendable -- via simple class extension
 * It's embeddable -- into your Java web application making testing your app easier

I have seen many quick'n'dirty proxies posted in source form on the web such as in a blog.
I've found such proxies to support a limited HTTP subset, such as only a GET request, or to suffer other implementation problems such as performance issues or URL escaping bugs.
Disappointed at the situation, I set out to create a simple one that works well and that is well tested so I know it works.
I suggest you use a well tested proxy instead of something non-tested that is perhaps better described as a proof-of-concept.

If you need something more sophisticated than there are some alternatives listed at the bottom of this page.

This proxy depends on [Apache HttpClient](http://hc.apache.org/httpcomponents-client-ga/), which offers another point of extension for this proxy.
At some point I may write an alternative that uses the JDK and thus doesn't have any dependencies, which is desirable.
In the meantime, you'll have to add the jar files for this and its dependencies:

     +- org.apache.httpcomponents:httpclient:jar:4.5.13:compile
        +- org.apache.httpcomponents:httpcore:jar:4.4.13:compile
        |  +- commons-logging:commons-logging:jar:1.2:compile
        |  \- commons-codec:commons-codec:jar:1.11:compile

This proxy supports HttpClient 4.3, and newer version too.
If you need to support _older_ HttpClient versions, namely 4.1 and 4.2, then use  the 1.8 version of this proxy.

As of version 1.5 of the proxy, there is the ability to parameterize your proxy URL, allowing you to use
the same web.xml servlet specification for multiple target servers. It follows the
[URI Template RFC, Level 1](http://tools.ietf.org/html/rfc6570). Special query
parameters (see the examples below) sent from the client to the ProxyServlet will
map to the matching URL template, replacing arguments in the proxy's targetUri as
specified in the web.xml.  To use this, you must use a subclass of the base servlet.
IMPORTANT! The template substitutions must be placed in the query string, even when using
HTTP POST. Other application parameters can be in your POSTed url-encoded-form string; just not
proxyArgs.

See [CHANGES.md](CHANGES.md) for a history of changes.

Build & Installation
------------

Simply build the jar using "mvn package" at the command line.
The jar is built to "target/smiley-http-proxy-servlet-VERSION.jar".
You don't have to build the jar if you aren't modifying the code, since released
versions are deployed to maven-central.  If you are using maven then you can
add this to your dependencies in your pom like so:
(Note: the version below is not necessarily the latest.)

```xml
<dependency>
    <groupId>org.mitre.dsmiley.httpproxy</groupId>
    <artifactId>smiley-http-proxy-servlet</artifactId>
    <version>1.12.1</version>
</dependency>
```
Ivy and other dependency managers can be used as well.


Configuration
-------------
### Parameters

The following is a list of parameters that can be configured

+ log: A boolean parameter name to enable logging of input and target URLs to the servlet log.
+ forwardip: A boolean parameter name to enable forwarding of the client IP
+ preserveHost: A boolean parameter name to keep HOST parameter as-is  
+ preserveCookies: A boolean parameter name to keep COOKIES as-is
+ preserveCookiePath: A boolean parameter name to keep cookie path unchanged in Set-Cookie server response header
+ http.protocol.handle-redirects: A boolean parameter name to have auto-handle redirects
+ http.socket.timeout: A integer parameter name to set the socket connection timeout (millis)
+ http.read.timeout: A integer parameter name to set the socket read timeout (millis)
+ http.connectionrequest.timeout: A integer parameter name to set the connection request timeout (millis)
+ http.maxConnections: A integer parameter name to set max connection number
+ useSystemProperties: A boolean parameter whether to use JVM-defined system properties to configure various networking aspects.
+ targetUri: The parameter name for the target (destination) URI to proxy to.


### Servlet

Here's an example excerpt of a web.xml file to communicate to a Solr server:

```xml
<servlet>
  <servlet-name>solr</servlet-name>
  <servlet-class>org.mitre.dsmiley.httpproxy.ProxyServlet</servlet-class>
  <init-param>
    <param-name>targetUri</param-name>
    <param-value>http://solrserver:8983/solr</param-value>
  </init-param>
  <init-param>
    <param-name>log</param-name>
    <param-value>true</param-value>
  </init-param>
</servlet>
<servlet-mapping>
  <servlet-name>solr</servlet-name>
  <url-pattern>/solr/*</url-pattern>
</servlet-mapping>
```

Here's an example with a parameterized proxy URL matching query parameters
_subHost, _port, and _path such as
"http://mywebapp/cluster/subpath?_subHost=namenode&_port=8080&_path=monitor". Note the different
proxy servlet class. The leading underscore is not mandatory but it's good to differentiate
them from the normal query parameters in case of a conflict.:

```xml
<servlet>
  <servlet-name>clusterProxy</servlet-name>
  <servlet-class>org.mitre.dsmiley.httpproxy.URITemplateProxyServlet</servlet-class>
  <init-param>
    <param-name>targetUri</param-name>
    <param-value>http://{_subHost}.behindfirewall.mycompany.com:{_port}/{_path}</param-value>
  </init-param>
  <init-param>
    <param-name>log</param-name>
    <param-value>true</param-value>
  </init-param>
</servlet>

<servlet-mapping>
  <servlet-name>clusterProxy</servlet-name>
  <url-pattern>/mywebapp/cluster/*</url-pattern>
</servlet-mapping>
```

### SpringMVC

If you are using **SpringMVC**, then an alternative is to use its
[ServletWrappingController](http://static.springsource.org/spring/docs/3.0.x/api/org/springframework/web/servlet/mvc/ServletWrappingController.html)
so that you can configure this servlet via Spring, which is supremely flexible, instead of having to modify your web.xml. However, note that some
customization may be needed to divide the URL at the proxied portion; see [Issue #15](https://github.com/mitre/HTTP-Proxy-Servlet/issues/15).

### Spring Boot

If you are using **Spring Boot**, then consider this basic configuration:

```java
@Configuration
public class SolrProxyServletConfiguration implements EnvironmentAware {

  @Bean
  public ServletRegistrationBean servletRegistrationBean() {
    ServletRegistrationBean servletRegistrationBean = new ServletRegistrationBean(new ProxyServlet(), propertyResolver.getProperty("servlet_url"));
    servletRegistrationBean.addInitParameter(ProxyServlet.P_TARGET_URI, propertyResolver.getProperty("target_url"));
    servletRegistrationBean.addInitParameter(ProxyServlet.P_LOG, propertyResolver.getProperty("logging_enabled", "false"));
    return servletRegistrationBean;
  }

  private RelaxedPropertyResolver propertyResolver;

  @Override
  public void setEnvironment(Environment environment) {
    this.propertyResolver = new RelaxedPropertyResolver(environment, "proxy.solr.");
  }
}
```
if you use Spring Boot 2.x,you can try this:
```java
@Configuration
public class SolrProxyServletConfiguration implements EnvironmentAware {

    @Bean
    public ServletRegistrationBean servletRegistrationBean() {
        Properties properties= (Properties) bindResult.get();
        ServletRegistrationBean servletRegistrationBean = new ServletRegistrationBean(new ProxyServlet(), properties.getProperty("servlet_url"));
        servletRegistrationBean.addInitParameter(ProxyServlet.P_TARGET_URI, properties.getProperty("target_url"));
        servletRegistrationBean.addInitParameter(ProxyServlet.P_LOG, properties.getProperty("logging_enabled", "false"));
        return servletRegistrationBean;
    }

    private BindResult bindResult;

    @Override
    public void setEnvironment(Environment environment) {
        Iterable sources = ConfigurationPropertySources.get(environment);
        Binder binder = new Binder(sources);
        BindResult bindResult = binder.bind("proxy.solr", Properties.class);
        this.bindResult = bindResult;
    }
}
```

and properties in `application.yml`:

```yaml
proxy:
    solr:
        servlet_url: /solr/*
        target_url: http://solrserver:8983/solr
```

It may be the case that Spring Boot (or Spring MVC) is consuming the servlet input stream before the servlet gets it, which is a problem.  
See [Issue #83](https://github.com/mitre/HTTP-Proxy-Servlet/issues/83#issuecomment-307216795) RE disabling `FilterRegistrationBean`.

### Dropwizard

Addition of Smiley's proxy to Dropwizard is very straightforward.   

Add a new property in the Dropwizard app `.yml` file

```
targetUri: http://foo.com/api  
```

Create a new configuration property

```java
    @NotEmpty
    private String targetUri = "";

    @JsonProperty("targetUri")
    public String getTargetUri() {
        return targetUri;
    }  
```

Then register Smiley's proxy servlet with Jetty through the Dropwizard service's App `run()` method.

```java
@Override
    public void run(final ShepherdServiceConfiguration configuration,
        final Environment environment) {

        environment.getApplicationContext()
            .addServlet("org.mitre.dsmiley.httpproxy.ProxyServlet", "foo/*")
            .setInitParameter("targetUri", configuration.getTargetUri());  
```

Alternatives
-------------
This servlet is intentionally simple and limited in scope.  As such it may not meet your needs, so consider looking at these alternatives:
* Jetty's ProxyServlet: https://www.eclipse.org/jetty/javadoc/jetty-9/org/eclipse/jetty/proxy/ProxyServlet.html  This is perhaps the closest competitor (simple, limited scope, no dependencies), and may very well already be on your classpath.
* Netflix's Zuul: https://github.com/Netflix/zuul
* Charon: https://github.com/mkopylec/charon-spring-boot-starter
