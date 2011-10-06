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
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicHttpEntityEnclosingRequest;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;

/**
 * An HTTP reverse proxy/gateway servlet. It is designed to be extended for customization
 * if desired. Most of the work is handled by
 * <a href="http://hc.apache.org/httpcomponents-client-ga/">Apache HttpClient</a>.
 * <p>
 *   There are alternatives to a servlet based proxy such as Apache mod_proxy if that is available to you. However
 *   this servlet is easily customizable by Java, secure-able by your web application's security (e.g. spring-security),
 *   and is portable across servlet engines.
 * </p>
 * <p>
 *   Inspiration: http://httpd.apache.org/docs/2.0/mod/mod_proxy.html
 * </p>
 */
public class ProxyServlet extends HttpServlet
{

  /* INIT PARAMETER NAME CONSTANTS */

  /** The target proxy host init parameter. */
  public static final String P_PROXY_HOST = "proxyHost";
  /** The target proxy port init parameter. */
  public static final String P_PROXY_PORT = "proxyPort";
  /** The target proxy path init parameter. This follows the port. Is should be blank or start with a '/'. */
  public static final String P_PROXY_PATH = "proxyPath";
  /** A boolean parameter then when enabled will log input and target URLs to the servlet log. */
  public static final String P_LOG = "log";

  /* MISC */

  /** The host to which we are proxying requests. */
  private String proxyHost;

  /** The port on the proxy host to which we are proxying requests. Default value is 80. */
  private int proxyPort = 80;

  /** The (optional) path on the proxy host to which we are proxying requests. Default value is "". */
  private String proxyPath = "";

  private boolean doLog = false;

  @Override
  public void init(ServletConfig servletConfig) throws ServletException {
    super.init(servletConfig);
    String stringProxyHostNew = servletConfig.getInitParameter(P_PROXY_HOST);
    if (stringProxyHostNew == null || stringProxyHostNew.length() == 0) {
      throw new IllegalArgumentException("Proxy host not set, please set init-param 'proxyHost' in web.xml");
    }
    this.proxyHost = stringProxyHostNew;
    String stringProxyPortNew = servletConfig.getInitParameter(P_PROXY_PORT);
    if (stringProxyPortNew != null && stringProxyPortNew.length() > 0) {
      this.proxyPort = Integer.parseInt(stringProxyPortNew);
    }
    String stringProxyPathNew = servletConfig.getInitParameter(P_PROXY_PATH);
    if (stringProxyPathNew != null && stringProxyPathNew.length() > 0) {
      this.proxyPath = stringProxyPathNew;
    }
    String stringDoLog = servletConfig.getInitParameter(P_LOG);
    if (stringDoLog != null && stringDoLog.length() > 0) {
      this.doLog = Boolean.parseBoolean(stringDoLog);
    }
  }

  @Override
  protected void service(HttpServletRequest servletRequest, HttpServletResponse servletResponse) throws ServletException, IOException {
    InputStream is = servletRequest.getInputStream();
    try {
      //note: we won't transfer the protocol version because I'm not sure it would truly be compatible
      BasicHttpEntityEnclosingRequest proxyRequest = new BasicHttpEntityEnclosingRequest(servletRequest.getMethod(),getProxyURL(servletRequest));
      copyRequestHeaders(proxyRequest, servletRequest);
      proxyRequest.setEntity(new InputStreamEntity(is, servletRequest.getContentLength()));
      this.executeProxyRequest(proxyRequest,servletRequest,servletResponse);
    } finally {
      closeQuietly(is);
    }
  }

  protected void closeQuietly(Closeable closeable) {
    try {
      closeable.close();
    } catch (IOException e) {
      log(e.getMessage(),e);
    }
  }

  /**
   * Executes the {@link HttpMethod} passed in and sends the proxy response
   * back to the client via the given {@link HttpServletResponse}
   *
   * @param proxyRequest An object representing the proxy request to be made
   * @param servletResponse    An object by which we can send the proxied
   *                               response back to the client
   * @throws IOException      Can be thrown by the {@link HttpClient}.executeMethod
   * @throws ServletException Can be thrown to indicate that another error has occurred
   */
  private void executeProxyRequest(
      HttpRequest proxyRequest,
      HttpServletRequest servletRequest,
      HttpServletResponse servletResponse)
      throws IOException, ServletException {

    if (doLog) {
      log("proxy " + servletRequest.getMethod() + " uri: " + servletRequest.getRequestURI() + " -- " + proxyRequest.getRequestLine().getUri());
    }

    // Create a default HttpClient
    HttpClient proxyClient = createHttpClient();

    // Execute the request
    HttpHost proxyHostTarget = new HttpHost(proxyHost, proxyPort,"http");

    HttpResponse proxyResponse = proxyClient.execute(proxyHostTarget,proxyRequest);
    int statusCode = proxyResponse.getStatusLine().getStatusCode();

    //TODO check this
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
      String stringMyHostName = servletRequest.getServerName();
      if (servletRequest.getServerPort() != 80) {
        stringMyHostName += ":" + servletRequest.getServerPort();
      }
      stringMyHostName += servletRequest.getContextPath();
      servletResponse.sendRedirect(locationHeader.getValue().replace(getProxyHostAndPort() + this.proxyPath, stringMyHostName));
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
      return;
    }

    // Pass the response code. This method with the "reason phrase" may be deprecated but it's the only way to pass that
    //  along.
    servletResponse.setStatus(statusCode, proxyResponse.getStatusLine().getReasonPhrase());

    copyResponseHeaders(proxyResponse, servletResponse);

    // Send the content to the client
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

  /** Pass response headers back to the client. */
  protected void copyResponseHeaders(HttpResponse hcResponse, HttpServletResponse httpServletResponse) {
    for (Header header : hcResponse.getAllHeaders()) {
      httpServletResponse.addHeader(header.getName(),header.getValue());
    }
  }

  protected HttpClient createHttpClient() {
    HttpClient hc = new DefaultHttpClient();
    //TODO
    //hcRequest.setFollowRedirects(false);
    return hc;
  }

  public String getServletInfo() {
    return "A proxy servlet by David Smiley, dsmiley@mitre.org";
  }

  /**
   * Retrieves all of the headers from the servlet request and sets them on
   * the proxy request
   */
  protected void copyRequestHeaders(HttpRequest hcRequest, HttpServletRequest servletRequest) {
    // Get an Enumeration of all of the header names sent by the client
    Enumeration enumerationOfHeaderNames = servletRequest.getHeaderNames();
    while (enumerationOfHeaderNames.hasMoreElements()) {
      String stringHeaderName = (String) enumerationOfHeaderNames.nextElement();
      if (stringHeaderName.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH))
        continue;
      // As per the Java Servlet API 2.5 documentation:
      //		Some headers, such as Accept-Language can be sent by clients
      //		as several headers each with a different value rather than
      //		sending the header as a comma separated list.
      // Thus, we get an Enumeration of the header values sent by the client
      Enumeration enumerationOfHeaderValues = servletRequest.getHeaders(stringHeaderName);
      while (enumerationOfHeaderValues.hasMoreElements()) {
        String stringHeaderValue = (String) enumerationOfHeaderValues.nextElement();
        // In case the proxy host is running multiple virtual servers,
        // rewrite the Host header to ensure that we get content from
        // the correct virtual server
        if (stringHeaderName.equalsIgnoreCase(HttpHeaders.HOST)) {
          stringHeaderValue = getProxyHostAndPort();
        }
        hcRequest.addHeader(stringHeaderName, stringHeaderValue);
      }
    }
  }

  private String getProxyURL(HttpServletRequest servletRequest) {
    // Set the protocol to HTTP
    String stringProxyURL = "http://" + this.getProxyHostAndPort();
    // Check if we are proxying to a path other that the document root
    if (!this.proxyPath.equals("")) {
      stringProxyURL += this.proxyPath;
    }
    // Handle the path given to the servlet
    if (servletRequest.getPathInfo() != null) {
      stringProxyURL += servletRequest.getPathInfo();
    }
    // Handle the query string
    if (servletRequest.getQueryString() != null) {
      stringProxyURL += "?" + servletRequest.getQueryString();
    }
    return stringProxyURL;
  }

  private String getProxyHostAndPort() {
    if (this.proxyPort == 80) {
      return this.proxyHost;
    } else {
      return this.proxyHost + ":" + this.proxyPort;
    }
  }

}
