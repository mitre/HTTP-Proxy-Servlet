package org.mitre.dsmiley.httpproxy; //originally net.edwardstx

/**
 * Copyright MITRE
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.http.*;
import org.apache.http.client.HttpClient;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicHttpEntityEnclosingRequest;
import org.apache.http.util.EntityUtils;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Enumeration;

/**
 * An HTTP reverse proxy/gateway servlet. It is designed to be extended for customization
 * if desired. Most of the work is handled by
 * <a href="http://hc.apache.org/httpcomponents-client-ga/">Apache HttpClient</a>.
 * <p>
 *   There are alternatives to a servlet based proxy such as Apache mod_proxy if that is available to you. However
 *   this servlet is easily customizable by Java, secure-able by your web application's security (e.g. spring-security),
 *   portable across servlet engines, and is embeddable into another web application.
 * </p>
 * <p>
 *   Inspiration: http://httpd.apache.org/docs/2.0/mod/mod_proxy.html
 * </p>
 */
public class ProxyServlet extends HttpServlet
{

  /* INIT PARAMETER NAME CONSTANTS */

  /** A boolean parameter then when enabled will log input and target URLs to the servlet log. */
  public static final String P_LOG = "log";

  /* MISC */

  protected boolean doLog = false;
  protected URI targetUri;
  protected HttpClient proxyClient;

  @Override
  public String getServletInfo() {
    return "A proxy servlet by David Smiley, dsmiley@mitre.org";
  }

  @Override
  public void init(ServletConfig servletConfig) throws ServletException {
    super.init(servletConfig);

    String doLogStr = servletConfig.getInitParameter(P_LOG);
    if (doLogStr != null) {
      this.doLog = Boolean.parseBoolean(doLogStr);
    }

    try {
      targetUri = new URI(servletConfig.getInitParameter("targetUri"));
    } catch (Exception e) {
      throw new RuntimeException("Trying to process targetUri init parameter: "+e,e);
    }

    proxyClient = createHttpClient();
  }

  /** Called from {@link #init(javax.servlet.ServletConfig)}. HttpClient offers many opportunities for customization. */
  protected HttpClient createHttpClient() {
    HttpClient hc = new DefaultHttpClient();
    String scParam = getServletConfig().getInitParameter(ClientPNames.HANDLE_REDIRECTS);
    hc.getParams().setBooleanParameter(ClientPNames.HANDLE_REDIRECTS, scParam == null ? true : Boolean.valueOf(scParam));
    return hc;
  }

  @Override
  public void destroy() {
    //shutdown() must be called according to documentation.
    proxyClient.getConnectionManager().shutdown();
    super.destroy();
  }

  @Override
  protected void service(HttpServletRequest servletRequest, HttpServletResponse servletResponse) throws ServletException, IOException {
    // Make the Request
    //note: we won't transfer the protocol version because I'm not sure it would truly be compatible
    BasicHttpEntityEnclosingRequest proxyRequest =
        new BasicHttpEntityEnclosingRequest(servletRequest.getMethod(), rewriteUrlFromRequest(servletRequest));
    
    copyRequestHeaders(servletRequest, proxyRequest);

    // Add the input entity (streamed) then execute the request.
    HttpResponse proxyResponse = null;
    InputStream servletRequestInputStream = servletRequest.getInputStream();
    try {
      proxyRequest.setEntity(new InputStreamEntity(servletRequestInputStream, servletRequest.getContentLength()));

      // Execute the request
      if (doLog) {
        log("proxy " + servletRequest.getMethod() + " uri: " + servletRequest.getRequestURI() + " -- " + proxyRequest.getRequestLine().getUri());
      }
      proxyResponse = proxyClient.execute(URIUtils.extractHost(targetUri), proxyRequest);
    } finally {
      closeQuietly(servletRequestInputStream);
    }

    // Process the response
    int statusCode = proxyResponse.getStatusLine().getStatusCode();

    // Check if the proxy response is a redirect
    // The following code is adapted from org.tigris.noodle.filters.CheckForRedirect
    if (statusCode >= HttpServletResponse.SC_MULTIPLE_CHOICES /* 300 */
        && statusCode < HttpServletResponse.SC_NOT_MODIFIED /* 304 */) {
      Header locationHeader = proxyResponse.getLastHeader(HttpHeaders.LOCATION);
      if (locationHeader == null) {
        throw new ServletException("Recieved status code: " + statusCode
            + " but no " + HttpHeaders.LOCATION + " header was found in the response");
      }
      // Modify the redirect to go to this proxy servlet rather that the proxied host
      String locStr = rewriteUrlFromResponse(servletRequest, locationHeader.getValue());

      servletResponse.sendRedirect(locStr);
      EntityUtils.consume(proxyResponse.getEntity());
      return;
    } else if (statusCode == HttpServletResponse.SC_NOT_MODIFIED) {
      // 304 needs special handling.  See:
      // http://www.ics.uci.edu/pub/ietf/http/rfc1945.html#Code304
      // We get a 304 whenever passed an 'If-Modified-Since'
      // header and the data on disk has not changed; server
      // responds w/ a 304 saying I'm not going to send the
      // body because the file has not changed.
      servletResponse.setIntHeader(HttpHeaders.CONTENT_LENGTH, 0);
      servletResponse.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
      EntityUtils.consume(proxyResponse.getEntity());
      return;
    }

    // Pass the response code. This method with the "reason phrase" is deprecated but it's the only way to pass the
    //  reason along too.
    //noinspection deprecation
    servletResponse.setStatus(statusCode, proxyResponse.getStatusLine().getReasonPhrase());

    copyResponseHeaders(proxyResponse, servletResponse);

    // Send the content to the client
    copyResponseEntity(proxyResponse, servletResponse);
  }

  protected void closeQuietly(Closeable closeable) {
    try {
      closeable.close();
    } catch (IOException e) {
      log(e.getMessage(),e);
    }
  }

  /** Copy request headers from the servlet client to the proxy request. */
  protected void copyRequestHeaders(HttpServletRequest servletRequest, HttpRequest proxyRequest) {
    // Get an Enumeration of all of the header names sent by the client
    Enumeration enumerationOfHeaderNames = servletRequest.getHeaderNames();
    while (enumerationOfHeaderNames.hasMoreElements()) {
      String headerName = (String) enumerationOfHeaderNames.nextElement();
      if (headerName.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH))
        continue;
      // As per the Java Servlet API 2.5 documentation:
      //		Some headers, such as Accept-Language can be sent by clients
      //		as several headers each with a different value rather than
      //		sending the header as a comma separated list.
      // Thus, we get an Enumeration of the header values sent by the client
      Enumeration headers = servletRequest.getHeaders(headerName);
      while (headers.hasMoreElements()) {
        String headerValue = (String) headers.nextElement();
        // In case the proxy host is running multiple virtual servers,
        // rewrite the Host header to ensure that we get content from
        // the correct virtual server
        if (headerName.equalsIgnoreCase(HttpHeaders.HOST)) {
          HttpHost host = URIUtils.extractHost(this.targetUri);
          headerValue = host.getHostName();
          if (host.getPort() != -1)
            headerValue += ":"+host.getPort();
        }
        proxyRequest.addHeader(headerName, headerValue);
      }
    }
  }

  /** Copy proxied response headers back to the servlet client. */
  protected void copyResponseHeaders(HttpResponse proxyResponse, HttpServletResponse servletResponse) {
    for (Header header : proxyResponse.getAllHeaders()) {
      servletResponse.addHeader(header.getName(), header.getValue());
    }
  }

  /** Copy response body data (the entity) from the proxy to the servlet client. */
  private void copyResponseEntity(HttpResponse proxyResponse, HttpServletResponse servletResponse) throws IOException {
    HttpEntity entity = proxyResponse.getEntity();
    if (entity != null) {
      OutputStream servletOutputStream = servletResponse.getOutputStream();
      try {
        entity.writeTo(servletOutputStream);
      } finally {
        closeQuietly(servletOutputStream);
      }
    }
  }
  
  private String rewriteUrlFromRequest(HttpServletRequest servletRequest) {
    StringBuilder uri = new StringBuilder(500);
    uri.append(this.targetUri.toString());
    // Handle the path given to the servlet
    if (servletRequest.getPathInfo() != null) {
      uri.append(servletRequest.getPathInfo());
    }
    // Handle the query string
    if (servletRequest.getQueryString() != null) {
      uri.append('?').append(servletRequest.getQueryString());
    }
    return uri.toString();
  }

  private String rewriteUrlFromResponse(HttpServletRequest servletRequest, String theUrl) {
    //TODO document example paths
    if (theUrl.startsWith(this.targetUri.toString())) {
      String curUrl = servletRequest.getRequestURL().toString();//no query
      String pathInfo = servletRequest.getPathInfo();
      if (pathInfo != null) {
        assert curUrl.endsWith(pathInfo);
        curUrl = curUrl.substring(0,curUrl.length()-pathInfo.length());//take pathInfo off
      }
      theUrl = curUrl+theUrl.substring(this.targetUri.toString().length());
    }
    return theUrl;
  }

}
