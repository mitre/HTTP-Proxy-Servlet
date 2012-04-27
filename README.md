Smiley's HTTP Proxy Servlet
===========================

This is an HTTP Proxy (aka gateway) in the form of a Java servlet.  An HTTP proxy is useful for AJAX applications to communicate with web accessible services on hosts other than where the web application is hosted.

This is hardly the first proxy, so why did I write it and thus why might you use it?

 * It's simple -- a single source file implementation
 * It's tested -- have confidence it works
 * It's securable -- via Java EE web.xml or via a servlet filter such as [Spring-Security]([http://static.springsource.org/spring-security/site/)
 * It's extendible -- via simple class extension
 * It's embeddable -- into your Java web application making testing your app easier

I have seen many quick'n'dirty proxies posted in source form on the web such as in a blog.  I've found such proxies to support a limited HTTP subset, such as only a GET request, or to suffer other implementation problems such as performance issues or URL escaping bugs.  Disappointed at the situation, I set out to create a simple one that works well and that is well tested so I know it works.  I suggest you use a well tested proxy instead of something non-tested that is perhaps better described as a proof-of-concept.

This proxy depends on [Apache HttpClient](http://hc.apache.org/httpcomponents-client-ga/), which offers another point of extension for this proxy.  At some point I may write an alternative that uses the JDK and thus doesn't have any dependencies, which is desirable. In the mean time, you'll have to add the jar files for this and its dependencies:

     +- org.apache.httpcomponents:httpclient:jar:4.1.2:compile
        +- org.apache.httpcomponents:httpcore:jar:4.1.2:compile
        |  +- commons-logging:commons-logging:jar:1.1.1:compile
        |  \- commons-codec:commons-codec:jar:1.4:compile
 

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