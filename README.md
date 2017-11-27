Smiley's HTTP Proxy Servlet
===========================

This is an HTTP Proxy (aka gateway) in the form of a Java servlet.  An HTTP proxy is useful for AJAX applications to communicate with web accessible services on hosts other than where the web application is hosted.

This is hardly the first proxy, so why did I write it and thus why might you use it?

 * It's simple -- a single source file implementation
 * It's tested -- have confidence it works [![Build Status](https://travis-ci.org/mitre/HTTP-Proxy-Servlet.png)](https://travis-ci.org/mitre/HTTP-Proxy-Servlet)
 * It's securable -- via Java EE web.xml or via a servlet filter such as [Spring-Security]([http://static.springsource.org/spring-security/site/)
 * It's extendible -- via simple class extension
 * It's embeddable -- into your Java web application making testing your app easier

I have seen many quick'n'dirty proxies posted in source form on the web such as in a blog.
I've found such proxies to support a limited HTTP subset, such as only a GET request, or to suffer other implementation problems such as performance issues or URL escaping bugs.
Disappointed at the situation, I set out to create a simple one that works well and that is well tested so I know it works.
I suggest you use a well tested proxy instead of something non-tested that is perhaps better described as a proof-of-concept.

This proxy depends on [Apache HttpClient](http://hc.apache.org/httpcomponents-client-ga/), which offers another point of extension for this proxy.
At some point I may write an alternative that uses the JDK and thus doesn't have any dependencies, which is desirable.
In the mean time, you'll have to add the jar files for this and its dependencies:

     +- org.apache.httpcomponents:httpclient:jar:4.5.2:compile
        +- org.apache.httpcomponents:httpcore:jar:4.4.4:compile
        |  +- commons-logging:commons-logging:jar:1.2:compile
        |  \- commons-codec:commons-codec:jar:1.9:compile

This proxy supports HttpClient 4.3, and newer version too.
If you need to support _older_ HttpClient versions, namely 4.1 and 4.2, then use  the 1.8 version of this proxy.

As of version 1.4 of the proxy, it will by default recognize "http.proxy" and
 most other standard Java system properties.

As of version 1.5 of the proxy, there is the ability to parameterize your proxy URL, allowing you to use
the same web.xml servlet specification for multiple target servers. It follows the
[URI Template RFC, Level 1](http://tools.ietf.org/html/rfc6570). Special query 
parameters (see the examples below) sent from the client to the ProxyServlet will 
map to the matching URL template, replacing arguments in the proxy's targetUri as
specified in the web.xml.  To use this, you must use a subclass of the base servlet.
IMPORTANT! The template substitutions must be placed in the query string, even when using
HTTP POST. Other application parameters can be in your POSTed url-encoded-form string; just not
proxyArgs.

Build & Installation
------------

Simply build the jar using "mvn package" at the command line.
The jar is built to "target/smiley-http-proxy-servlet-VERSION.jar".
You don't have to build the jar if you aren't modifying the code, since released
versions are deployed to maven-central.  If you are using maven then you can
add this to your dependencies in your pom like so:
(Note: the version below is not necessarily the latest.)

    <dependency>
        <groupId>org.mitre.dsmiley.httpproxy</groupId>
        <artifactId>smiley-http-proxy-servlet</artifactId>
        <version>1.10</version>
    </dependency>

Ivy and other dependency managers can be used as well.


Configuration
-------------

Here's an example excerpt of a web.xml file to communicate to a Solr server:

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

Here's an example with a parameterized proxy URL matching query parameters
_subHost, _port, and _path such as 
"http://mywebapp/cluster/subpath?_subHost=namenode&_port=8080&_path=monitor". Note the different
proxy servlet class. The leading underscore is not mandatory but it's good to differentiate
them from the normal query parameters in case of a conflict.:

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

If you are using **SpringMVC**, then an alternative is to use its
[ServletWrappingController](http://static.springsource.org/spring/docs/3.0.x/api/org/springframework/web/servlet/mvc/ServletWrappingController.html)
so that you can configure this servlet via Spring, which is supremely flexible, instead of having to modify your web.xml. However, note that some
customization may be needed to divide the URL at the proxied portion; see [Issue #15](https://github.com/mitre/HTTP-Proxy-Servlet/issues/15).

If you are using **Spring Boot**, then consider this basic configuration:

```java
@Configuration
public class SolrProxyServletConfiguration implements EnvironmentAware {

  @Bean
  public ServletRegistrationBean servletRegistrationBean(){
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

and properties in `application.yml`:

```
proxy:
    solr:
        servlet_url: /solr/*
        target_url: http://solrserver:8983/solr
```
