/*
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

package org.mitre.dsmiley.httpproxy;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.BitSet;
import java.util.Enumeration;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * An HTTP reverse proxy/gateway servlet. It is designed to be extended for customization
 * if desired. Most of the work is handled by the JDK 11 HttpClient.
 * <p>
 *   There are alternatives to a servlet based proxy such as Apache mod_proxy if that is available to you. However
 *   this servlet is easily customizable by Java, secure-able by your web application's security (e.g. spring-security),
 *   portable across servlet engines, and is embeddable into another web application.
 * </p>
 * <p>
 *   Inspiration: http://httpd.apache.org/docs/2.0/mod/mod_proxy.html
 * </p>
 *
 * @author David Smiley dsmiley@apache.org
 */
@SuppressWarnings({"deprecation", "serial", "WeakerAccess"})
public class ProxyServlet extends HttpServlet {

  /* INIT PARAMETER NAME CONSTANTS */

  /** A boolean parameter name to enable logging of input and target URLs to the servlet log. */
  public static final String P_LOG = "log";

  /** A boolean parameter name to enable forwarding of the client IP  */
  public static final String P_FORWARDEDFOR = "forwardip";

  /** A boolean parameter name to keep HOST parameter as-is  */
  public static final String P_PRESERVEHOST = "preserveHost";

  /** A boolean parameter name to keep COOKIES as-is  */
  public static final String P_PRESERVECOOKIES = "preserveCookies";

  /** A boolean parameter name to keep COOKIE path as-is  */
  public static final String P_PRESERVECOOKIEPATH = "preserveCookiePath";

  /** A boolean parameter name to have auto-handle redirects */
  public static final String P_HANDLEREDIRECTS = "http.protocol.handle-redirects"; // ClientPNames.HANDLE_REDIRECTS

  /** An integer parameter name to set the socket connection timeout (millis) */
  public static final String P_CONNECTTIMEOUT = "http.socket.timeout"; // CoreConnectionPNames.SO_TIMEOUT

  /** An integer parameter name to set the socket read timeout (millis) */
  public static final String P_READTIMEOUT = "http.read.timeout";

  /** A boolean parameter to enable handling of compression in the servlet. If it is false, compressed streams are passed through unmodified. */
  public static final String P_HANDLECOMPRESSION = "handleCompression";

  /** The parameter name for the target (destination) URI to proxy to. */
  public static final String P_TARGET_URI = "targetUri";

  protected static final String ATTR_TARGET_URI =
          ProxyServlet.class.getSimpleName() + ".targetUri";
  protected static final String ATTR_TARGET_HOST =
          ProxyServlet.class.getSimpleName() + ".targetHost";

  /* MISC */

  protected boolean doLog = false;
  protected boolean doForwardIP = true;
  /** User agents shouldn't send the url fragment but what if it does? */
  protected boolean doSendUrlFragment = true;
  protected boolean doPreserveHost = false;
  protected boolean doPreserveCookies = false;
  protected boolean doPreserveCookiePath = false;
  protected boolean doHandleRedirects = false;
  protected boolean doHandleCompression = false;
  protected int connectTimeout = -1;
  protected int readTimeout = -1;

  //These next 3 are cached here, and should only be referred to in initialization logic. See the
  // ATTR_* parameters.
  /** From the configured parameter "targetUri". */
  protected String targetUri;
  protected URI targetUriObj;//new URI(targetUri)
  protected String targetHost;//host:port from targetUriObj

  private HttpClient proxyClient;

  @Override
  public String getServletInfo() {
    return "A proxy servlet by David Smiley, dsmiley@apache.org";
  }


  protected String getTargetUri(HttpServletRequest servletRequest) {
    return (String) servletRequest.getAttribute(ATTR_TARGET_URI);
  }

  protected String getTargetHost(HttpServletRequest servletRequest) {
    return (String) servletRequest.getAttribute(ATTR_TARGET_HOST);
  }

  /**
   * Reads a configuration parameter. By default it reads servlet init parameters but
   * it can be overridden.
   */
  protected String getConfigParam(String key) {
    return getServletConfig().getInitParameter(key);
  }

  @Override
  public void init() throws ServletException {
    String doLogStr = getConfigParam(P_LOG);
    if (doLogStr != null) {
      this.doLog = Boolean.parseBoolean(doLogStr);
    }

    String doForwardIPString = getConfigParam(P_FORWARDEDFOR);
    if (doForwardIPString != null) {
      this.doForwardIP = Boolean.parseBoolean(doForwardIPString);
    }

    String preserveHostString = getConfigParam(P_PRESERVEHOST);
    if (preserveHostString != null) {
      this.doPreserveHost = Boolean.parseBoolean(preserveHostString);
    }

    String preserveCookiesString = getConfigParam(P_PRESERVECOOKIES);
    if (preserveCookiesString != null) {
      this.doPreserveCookies = Boolean.parseBoolean(preserveCookiesString);
    }

    String preserveCookiePathString = getConfigParam(P_PRESERVECOOKIEPATH);
    if (preserveCookiePathString != null) {
      this.doPreserveCookiePath = Boolean.parseBoolean(preserveCookiePathString);
    }

    String handleRedirectsString = getConfigParam(P_HANDLEREDIRECTS);
    if (handleRedirectsString != null) {
      this.doHandleRedirects = Boolean.parseBoolean(handleRedirectsString);
    }

    String connectTimeoutString = getConfigParam(P_CONNECTTIMEOUT);
    if (connectTimeoutString != null) {
      this.connectTimeout = Integer.parseInt(connectTimeoutString);
    }

    String readTimeoutString = getConfigParam(P_READTIMEOUT);
    if (readTimeoutString != null) {
      this.readTimeout = Integer.parseInt(readTimeoutString);
    }

    String doHandleCompression = getConfigParam(P_HANDLECOMPRESSION);
    if (doHandleCompression != null) {
      this.doHandleCompression = Boolean.parseBoolean(doHandleCompression);
    }

    initTarget();//sets target*

    proxyClient = createHttpClient();
  }

  /**
   * Sub-classes can override specific behaviour of the HttpClient builder.
   */
  protected HttpClient.Builder buildClientBuilder() {
    HttpClient.Builder builder = HttpClient.newBuilder();
    
    if (doHandleRedirects) {
      builder.followRedirects(HttpClient.Redirect.NORMAL);
    } else {
      builder.followRedirects(HttpClient.Redirect.NEVER);
    }
    
    if (connectTimeout > 0) {
      builder.connectTimeout(Duration.ofMillis(connectTimeout));
    }
    
    return builder;
  }

  protected void initTarget() throws ServletException {
    targetUri = getConfigParam(P_TARGET_URI);
    if (targetUri == null)
      throw new ServletException(P_TARGET_URI+" is required.");
    //test it's valid
    try {
      targetUriObj = new URI(targetUri);
    } catch (Exception e) {
      throw new ServletException("Trying to process targetUri init parameter: "+e,e);
    }
    targetHost = extractHost(targetUriObj);
  }

  /**
   * Extract host:port from URI
   */
  private String extractHost(URI uri) {
    String host = uri.getHost();
    int port = uri.getPort();
    if (port == -1) {
      return host;
    }
    return host + ":" + port;
  }

  /**
   * Called from {@link #init(jakarta.servlet.ServletConfig)}.
   * HttpClient offers opportunities for customization.
   * In any case, it should be thread-safe.
   */
  protected HttpClient createHttpClient() {
    HttpClient.Builder clientBuilder = buildClientBuilder();
    
    return buildHttpClient(clientBuilder);
  }

  /**
   * Creates a HttpClient from the given builder. Meant as postprocessor
   * to possibly adapt the client builder prior to creating the
   * HttpClient.
   *
   * @param clientBuilder pre-configured client builder
   * @return HttpClient
   */
  protected HttpClient buildHttpClient(HttpClient.Builder clientBuilder) {
    return clientBuilder.build();
  }

  /**
   * Creates a {@code HttpClient.Builder}. Meant as preprocessor to possibly
   * adapt the client builder prior to any configuration got applied.
   *
   * @return HttpClient builder
   */
  protected HttpClient.Builder getHttpClientBuilder() {
    return HttpClient.newBuilder();
  }

  /**
   * The http client used.
   * @see #createHttpClient()
   */
  protected HttpClient getProxyClient() {
    return proxyClient;
  }

  @Override
  public void destroy() {
    //Usually, clients implement Closeable (as of Java 21+):
    if (proxyClient instanceof AutoCloseable) {
      try {
        ((AutoCloseable) proxyClient).close();
      } catch (Exception e) {
        log("While destroying servlet, shutting down HttpClient: "+e, e);
      }
    }
    super.destroy();
  }

  @Override
  protected void service(HttpServletRequest servletRequest, HttpServletResponse servletResponse)
      throws ServletException, IOException {
    //initialize request attributes from caches if unset by a subclass by this point
    if (servletRequest.getAttribute(ATTR_TARGET_URI) == null) {
      servletRequest.setAttribute(ATTR_TARGET_URI, targetUri);
    }
    if (servletRequest.getAttribute(ATTR_TARGET_HOST) == null) {
      servletRequest.setAttribute(ATTR_TARGET_HOST, targetHost);
    }

    // Make the Request
    //note: we won't transfer the protocol version because I'm not sure it would truly be compatible
    String method = servletRequest.getMethod();
    String proxyRequestUri = rewriteUrlFromRequest(servletRequest);
    
    HttpRequest.Builder proxyRequestBuilder = HttpRequest.newBuilder()
        .uri(URI.create(proxyRequestUri));
    
    // Set method and body
    HttpRequest.BodyPublisher bodyPublisher;
    if (servletRequest.getHeader("Content-Length") != null ||
        servletRequest.getHeader("Transfer-Encoding") != null) {
      long contentLength = getContentLength(servletRequest);
      bodyPublisher = HttpRequest.BodyPublishers.ofInputStream(() -> {
        try {
          return servletRequest.getInputStream();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
    } else {
      bodyPublisher = HttpRequest.BodyPublishers.noBody();
    }
    
    proxyRequestBuilder.method(method, bodyPublisher);
    
    // Set timeout if configured
    if (readTimeout > 0) {
      proxyRequestBuilder.timeout(Duration.ofMillis(readTimeout));
    }
    
    // Copy request headers
    copyRequestHeaders(servletRequest, proxyRequestBuilder);
    setXForwardedForHeader(servletRequest, proxyRequestBuilder);
    
    HttpRequest proxyRequest = proxyRequestBuilder.build();
    
    HttpResponse<InputStream> proxyResponse = null;
    try {
      // Execute the request
      proxyResponse = doExecute(servletRequest, servletResponse, proxyRequest);

      // Process the response:
      int statusCode = proxyResponse.statusCode();
      servletResponse.setStatus(statusCode);

      // Copy response headers
      copyResponseHeaders(proxyResponse, servletRequest, servletResponse);

      if (statusCode == HttpServletResponse.SC_NOT_MODIFIED) {
        // 304 needs special handling.
        // Don't send body entity/content!
        servletResponse.setIntHeader("Content-Length", 0);
      } else {
        // Send the content to the client
        copyResponseEntity(proxyResponse, servletResponse, proxyRequest, servletRequest);
      }

    } catch (Exception e) {
      handleRequestException(proxyRequest, proxyResponse, e);
    } finally {
      // make sure the entire entity was consumed, so the connection is released
      if (proxyResponse != null && proxyResponse.body() != null) {
        try {
          proxyResponse.body().close();
        } catch (IOException e) {
          // Ignore
        }
      }
      //Note: Don't need to close servlet outputStream:
      // http://stackoverflow.com/questions/1159168/should-one-call-close-on-httpservletresponse-getoutputstream-getwriter
    }
  }

  protected void handleRequestException(HttpRequest proxyRequest, HttpResponse<InputStream> proxyResponse, Exception e) throws ServletException, IOException {
    // If the response is a chunked response, it is read to completion when
    // #close is called. If the sending site does not timeout or keeps sending,
    // the connection will be kept open indefinitely. Closing the response
    // object terminates the stream.
    if (proxyResponse != null && proxyResponse.body() != null) {
      try {
        proxyResponse.body().close();
      } catch (IOException ex) {
        // Ignore
      }
    }
    if (e instanceof RuntimeException)
      throw (RuntimeException)e;
    if (e instanceof ServletException)
      throw (ServletException)e;
    //noinspection ConstantConditions
    if (e instanceof IOException)
      throw (IOException) e;
    throw new RuntimeException(e);
  }

  protected HttpResponse<InputStream> doExecute(HttpServletRequest servletRequest, HttpServletResponse servletResponse,
                                   HttpRequest proxyRequest) throws IOException {
    if (doLog) {
      log("proxy " + servletRequest.getMethod() + " uri: " + servletRequest.getRequestURI() + " -- " +
              proxyRequest.uri());
    }
    try {
      return proxyClient.send(proxyRequest, HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Request interrupted", e);
    }
  }

  // Get the header value as a long in order to more correctly proxy very large requests
  private long getContentLength(HttpServletRequest request) {
    String contentLengthHeader = request.getHeader("Content-Length");
    if (contentLengthHeader != null) {
      return Long.parseLong(contentLengthHeader);
    }
    return -1L;
  }

  /** These are the "hop-by-hop" headers that should not be copied.
   * http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html
   */
  protected static final Set<String> hopByHopHeaders = Set.of(
      "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
      "te", "trailers", "transfer-encoding", "upgrade"
  );

  /**
   * Copy request headers from the servlet client to the proxy request.
   * This is easily overridden to add your own.
   */
  protected void copyRequestHeaders(HttpServletRequest servletRequest, HttpRequest.Builder proxyRequest) {
    // Get an Enumeration of all of the header names sent by the client
    @SuppressWarnings("unchecked")
    Enumeration<String> enumerationOfHeaderNames = servletRequest.getHeaderNames();
    while (enumerationOfHeaderNames.hasMoreElements()) {
      String headerName = enumerationOfHeaderNames.nextElement();
      copyRequestHeader(servletRequest, proxyRequest, headerName);
    }
  }

  /**
   * Copy a request header from the servlet client to the proxy request.
   * This is easily overridden to filter out certain headers if desired.
   */
  protected void copyRequestHeader(HttpServletRequest servletRequest, HttpRequest.Builder proxyRequest,
                                   String headerName) {
    //Instead the content-length is effectively set via InputStreamEntity
    if (headerName.equalsIgnoreCase("Content-Length"))
      return;
    if (hopByHopHeaders.contains(headerName.toLowerCase(Locale.ROOT)))
      return;
    // If compression is handled in the servlet, JDK http client needs to
    // control the Accept-Encoding header, not the client
    if (doHandleCompression && headerName.equalsIgnoreCase("Accept-Encoding"))
      return;

    @SuppressWarnings("unchecked")
    Enumeration<String> headers = servletRequest.getHeaders(headerName);
    while (headers.hasMoreElements()) {//sometimes more than one value
      String headerValue = headers.nextElement();
      // In case the proxy host is running multiple virtual servers,
      // rewrite the Host header to ensure that we get content from
      // the correct virtual server
      if (!doPreserveHost && headerName.equalsIgnoreCase("Host")) {
        headerValue = getTargetHost(servletRequest);
      } else if (!doPreserveCookies && headerName.equalsIgnoreCase("Cookie")) {
        headerValue = getRealCookie(headerValue);
      }
      proxyRequest.header(headerName, headerValue);
    }
  }

  private void setXForwardedForHeader(HttpServletRequest servletRequest,
                                      HttpRequest.Builder proxyRequest) {
    if (doForwardIP) {
      String forHeaderName = "X-Forwarded-For";
      String forHeader = servletRequest.getRemoteAddr();
      String existingForHeader = servletRequest.getHeader(forHeaderName);
      if (existingForHeader != null) {
        forHeader = existingForHeader + ", " + forHeader;
      }
      proxyRequest.setHeader(forHeaderName, forHeader);

      String protoHeaderName = "X-Forwarded-Proto";
      String protoHeader = servletRequest.getScheme();
      proxyRequest.setHeader(protoHeaderName, protoHeader);
    }
  }

  /** Copy proxied response headers back to the servlet client. */
  protected void copyResponseHeaders(HttpResponse<InputStream> proxyResponse, HttpServletRequest servletRequest,
                                     HttpServletResponse servletResponse) {
    for (var entry : proxyResponse.headers().map().entrySet()) {
      String headerName = entry.getKey();
      for (String headerValue : entry.getValue()) {
        copyResponseHeader(servletRequest, servletResponse, headerName, headerValue);
      }
    }
  }

  /** Copy a proxied response header back to the servlet client.
   * This is easily overwritten to filter out certain headers if desired.
   */
  protected void copyResponseHeader(HttpServletRequest servletRequest,
                                  HttpServletResponse servletResponse, String headerName, String headerValue) {
    if (hopByHopHeaders.contains(headerName.toLowerCase(Locale.ROOT)))
      return;
    if (headerName.equalsIgnoreCase("Set-Cookie") ||
            headerName.equalsIgnoreCase("Set-Cookie2")) {
      copyProxyCookie(servletRequest, servletResponse, headerValue);
    } else if (headerName.equalsIgnoreCase("Location")) {
      // LOCATION Header may have to be rewritten.
      servletResponse.addHeader(headerName, rewriteUrlFromResponse(servletRequest, headerValue));
    } else {
      servletResponse.addHeader(headerName, headerValue);
    }
  }

  /**
   * Copy cookie from the proxy to the servlet client.
   * Replaces cookie path to local path and renames cookie to avoid collisions.
   */
  protected void copyProxyCookie(HttpServletRequest servletRequest,
                                 HttpServletResponse servletResponse, String headerValue) {
    for (HttpCookie cookie : HttpCookie.parse(headerValue)) {
      Cookie servletCookie = createProxyCookie(servletRequest, cookie);
      servletResponse.addCookie(servletCookie);
    }
  }

  /**
   * Creates a proxy cookie from the original cookie.
   *
   * @param servletRequest original request
   * @param cookie original cookie
   * @return proxy cookie
   */
  protected Cookie createProxyCookie(HttpServletRequest servletRequest, HttpCookie cookie) {
    String proxyCookieName = getProxyCookieName(cookie);
    Cookie servletCookie = new Cookie(proxyCookieName, cookie.getValue());
    servletCookie.setPath(this.doPreserveCookiePath ?
       cookie.getPath() : // preserve original cookie path
       buildProxyCookiePath(servletRequest) //set to the path of the proxy servlet
    );
    servletCookie.setComment(cookie.getComment());
    servletCookie.setMaxAge((int) cookie.getMaxAge());
    // don't set cookie domain
    servletCookie.setSecure(servletRequest.isSecure() && cookie.getSecure());
    servletCookie.setVersion(cookie.getVersion());
    servletCookie.setHttpOnly(cookie.isHttpOnly());
    return servletCookie;
  }

  /**
   * Set cookie name prefixed with a proxy value so it won't collide with other cookies.
   *
   * @param cookie cookie to get proxy cookie name for
   * @return non-conflicting proxy cookie name
   */
  protected String getProxyCookieName(HttpCookie cookie) {
    //
    return doPreserveCookies ? cookie.getName() : getCookieNamePrefix(cookie.getName()) + cookie.getName();
  }

  /**
   * Create path for proxy cookie.
   *
   * @param servletRequest original request
   * @return proxy cookie path
   */
  protected String buildProxyCookiePath(HttpServletRequest servletRequest) {
    String path = servletRequest.getContextPath(); // path starts with / or is empty string
    path += servletRequest.getServletPath(); // servlet path starts with / or is empty string
    if (path.isEmpty()) {
      path = "/";
    }
    return path;
  }

  /**
   * Take any client cookies that were originally from the proxy and prepare them to send to the
   * proxy.  This relies on cookie headers being set correctly according to RFC 6265 Sec 5.4.
   * This also blocks any local cookies from being sent to the proxy.
   */
  protected String getRealCookie(String cookieValue) {
    StringBuilder escapedCookie = new StringBuilder();
    String cookies[] = cookieValue.split("[;,]");
    for (String cookie : cookies) {
      String cookieSplit[] = cookie.split("=");
      if (cookieSplit.length == 2) {
        String cookieName = cookieSplit[0].trim();
        if (cookieName.startsWith(getCookieNamePrefix(cookieName))) {
          cookieName = cookieName.substring(getCookieNamePrefix(cookieName).length());
          if (escapedCookie.length() > 0) {
            escapedCookie.append("; ");
          }
          escapedCookie.append(cookieName).append("=").append(cookieSplit[1].trim());
        }
      }
    }
    return escapedCookie.toString();
  }

  /** The string prefixing rewritten cookies. */
  protected String getCookieNamePrefix(String name) {
    return "!Proxy!" + getServletConfig().getServletName();
  }

  /** Copy response body data (the entity) from the proxy to the servlet client. */
  protected void copyResponseEntity(HttpResponse<InputStream> proxyResponse, HttpServletResponse servletResponse,
                                    HttpRequest proxyRequest, HttpServletRequest servletRequest)
          throws IOException {
    InputStream entity = proxyResponse.body();
    if (entity != null) {
      // Check if chunked based on headers
      List<String> transferEncoding = proxyResponse.headers().allValues("Transfer-Encoding");
      boolean isChunked = transferEncoding.stream().anyMatch(te -> te.toLowerCase().contains("chunked"));
      
      if (isChunked) {
        // Flush intermediate results before blocking on input -- needed for SSE
        InputStream is = entity;
        OutputStream os = servletResponse.getOutputStream();
        byte[] buffer = new byte[10 * 1024];
        int read;
        while ((read = is.read(buffer)) != -1) {
          os.write(buffer, 0, read);
          /*-
           * Issue in JDK HttpClient: if the stream from client is
           * compressed, it may delegate to GzipInputStream.
           * The #available implementation of InflaterInputStream (parent of
           * GzipInputStream) return 1 until EOF is reached. This is not
           * consistent with InputStream#available, which defines:
           *
           *   A single read or skip of this many bytes will not block,
           *   but may read or skip fewer bytes.
           *
           *  To work around this, a flush is issued always if compression
            *  is handled by the http client
           */
          if (doHandleCompression || is.available() == 0 /* next is.read will block */) {
            os.flush();
          }
        }
        // Entity closing/cleanup is done in the caller (#service)
      } else {
        OutputStream servletOutputStream = servletResponse.getOutputStream();
        entity.transferTo(servletOutputStream);
      }
    }
  }

  /**
   * Reads the request URI from {@code servletRequest} and rewrites it, considering targetUri.
   * It's used to make the new request.
   */
  protected String rewriteUrlFromRequest(HttpServletRequest servletRequest) {
    StringBuilder uri = new StringBuilder(500);
    uri.append(getTargetUri(servletRequest));
    // Handle the path given to the servlet
    String pathInfo = rewritePathInfoFromRequest(servletRequest);
    if (pathInfo != null) {//ex: /my/path.html
      // getPathInfo() returns decoded string, so we need encodeUriQuery to encode "%" characters
      uri.append(encodeUriQuery(pathInfo, true));
    }
    // Handle the query string & fragment
    String queryString = servletRequest.getQueryString();//ex:(following '?'): name=value&foo=bar#fragment
    String fragment = null;
    //split off fragment from queryString, updating queryString if found
    if (queryString != null) {
      int fragIdx = queryString.indexOf('#');
      if (fragIdx >= 0) {
        fragment = queryString.substring(fragIdx + 1);
        queryString = queryString.substring(0,fragIdx);
      }
    }

    queryString = rewriteQueryStringFromRequest(servletRequest, queryString);
    if (queryString != null && queryString.length() > 0) {
      uri.append('?');
      // queryString is not decoded, so we need encodeUriQuery not to encode "%" characters, to avoid double-encoding
      uri.append(encodeUriQuery(queryString, false));
    }

    if (doSendUrlFragment && fragment != null) {
      uri.append('#');
      // fragment is not decoded, so we need encodeUriQuery not to encode "%" characters, to avoid double-encoding
      uri.append(encodeUriQuery(fragment, false));
    }
    return uri.toString();
  }

  protected String rewriteQueryStringFromRequest(HttpServletRequest servletRequest, String queryString) {
    return queryString;
  }

  /**
   * Allow overrides of {@link jakarta.servlet.http.HttpServletRequest#getPathInfo()}.
   * Useful when url-pattern of servlet-mapping (web.xml) requires manipulation.
   */
  protected String rewritePathInfoFromRequest(HttpServletRequest servletRequest) {
    return servletRequest.getPathInfo();
  }

  /**
   * For a redirect response from the target server, this translates {@code theUrl} to redirect to
   * and translates it to one the original client can use.
   */
  protected String rewriteUrlFromResponse(HttpServletRequest servletRequest, String theUrl) {
    //TODO document example paths
    final String targetUri = getTargetUri(servletRequest);
    if (theUrl.startsWith(targetUri)) {
      /*-
       * The URL points back to the back-end server.
       * Instead of returning it verbatim we replace the target path with our
       * source path in a way that should instruct the original client to
       * request the URL pointed through this Proxy.
       * We do this by taking the current request and rewriting the path part
       * using this servlet's absolute path and the path from the returned URL
       * after the base target URL.
       */
      StringBuffer curUrl = servletRequest.getRequestURL();//no query
      int pos;
      // Skip the protocol part
      if ((pos = curUrl.indexOf("://"))>=0) {
        // Skip the authority part
        // + 3 to skip the separator between protocol and authority
        if ((pos = curUrl.indexOf("/", pos + 3)) >=0) {
          // Trim everything after the authority part.
          curUrl.setLength(pos);
        }
      }
      // Context path starts with a / if it is not blank
      curUrl.append(servletRequest.getContextPath());
      // Servlet path starts with a / if it is not blank
      curUrl.append(servletRequest.getServletPath());
      curUrl.append(theUrl, targetUri.length(), theUrl.length());
      return curUrl.toString();
    }
    return theUrl;
  }

  /** The target URI as configured. Not null. */
  public String getTargetUri() { return targetUri; }

  /**
   * Encodes characters in the query or fragment part of the URI.
   *
   * <p>Unfortunately, an incoming URI sometimes has characters disallowed by the spec.  HttpClient
   * insists that the outgoing proxied request has a valid URI because it uses Java's {@link URI}.
   * To be more forgiving, we must escape the problematic characters.  See the URI class for the
   * spec.
   *
   * @param in example: name=value&amp;foo=bar#fragment
   * @param encodePercent determine whether percent characters need to be encoded
   */
  protected CharSequence encodeUriQuery(CharSequence in, boolean encodePercent) {
    //Note that I can't simply use URI.java to encode because it will escape pre-existing escaped things.
    StringBuilder outBuf = null;
    Formatter formatter = null;
    for(int i = 0; i < in.length(); i++) {
      char c = in.charAt(i);
      boolean escape = true;
      if (c < 128) {
        if (asciiQueryChars.get(c) && !(encodePercent && c == '%')) {
          escape = false;
        }
      } else if (!Character.isISOControl(c) && !Character.isSpaceChar(c)) {//not-ascii
        escape = false;
      }
      if (!escape) {
        if (outBuf != null)
          outBuf.append(c);
      } else {
        //escape
        if (outBuf == null) {
          outBuf = new StringBuilder(in.length() + 5*3);
          outBuf.append(in,0,i);
          formatter = new Formatter(outBuf);
        }
        //leading %, 0 padded, width 2, capital hex
        formatter.format("%%%02X",(int)c);//TODO
      }
    }
    return outBuf != null ? outBuf : in;
  }

  protected static final BitSet asciiQueryChars;
  static {
    char[] c_unreserved = "_-!.~'()*".toCharArray();//plus alphanum
    char[] c_punct = ",;:$&+=".toCharArray();
    char[] c_reserved = "/@".toCharArray();//plus punct.  Exclude '?'; RFC-2616 3.2.2. Exclude '[', ']'; https://www.ietf.org/rfc/rfc1738.txt, unsafe characters
    asciiQueryChars = new BitSet(128);
    for(char c = 'a'; c <= 'z'; c++) asciiQueryChars.set(c);
    for(char c = 'A'; c <= 'Z'; c++) asciiQueryChars.set(c);
    for(char c = '0'; c <= '9'; c++) asciiQueryChars.set(c);
    for(char c : c_unreserved) asciiQueryChars.set(c);
    for(char c : c_punct) asciiQueryChars.set(c);
    for(char c : c_reserved) asciiQueryChars.set(c);

    asciiQueryChars.set('%');//leave existing percent escapes in place
  }

}
