package org.mitre.dsmiley.httpproxy;

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

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Enumeration;
import java.util.Formatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.AbortableHttpRequest;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicHttpEntityEnclosingRequest;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.message.HeaderGroup;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.util.EntityUtils;

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
 *
 * @author David Smiley dsmiley@mitre.org
 */
public class ProxyServlet extends HttpServlet {

  /* INIT PARAMETER NAME CONSTANTS */

  /** A boolean parameter name to enable logging of input and target URLs to the servlet log. */
  public static final String P_LOG = "log";

  /** A boolean parameter name to enable forwarding of the client IP  */
  public static final String P_FORWARDEDFOR = "forwardip";

  /** The parameter name for the target (destination) URI to proxy to. */
  private static final String P_TARGET_URI = "targetUri";
  
  private static final Pattern URL_ARG_PATTERN = Pattern.compile("(?:[$][1-9])|(?:\\{[a-zA-Z]\\w*\\})");

  private static final String UTF_8 = "UTF-8";

  /* MISC */

  protected boolean doLog = false;
  protected boolean doForwardIP = true;
  /** User agents shouldn't send the url fragment but what if it does? */
  protected boolean doSendUrlFragment = true;
  protected URI targetUriObj;
  /** targetUriObj.toString() */
  protected String targetUri;
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
    
    String doForwardIPString = servletConfig.getInitParameter(P_FORWARDEDFOR);
    if (doForwardIPString != null) {
      this.doForwardIP = Boolean.parseBoolean(doForwardIPString);
    }

    targetUri = servletConfig.getInitParameter(P_TARGET_URI);
    if (!(targetUri.contains("$") || targetUri.contains("{"))) {
      try {
        targetUriObj = new URI(targetUri);
      } catch (Exception e) {
        throw new RuntimeException("Trying to process targetUri init parameter: "+e,e);
      }
      targetUri = targetUriObj.toString();
    }
  
    HttpParams hcParams = new BasicHttpParams();
    readConfigParam(hcParams, ClientPNames.HANDLE_REDIRECTS, Boolean.class);
    proxyClient = createHttpClient(hcParams);
  }

  /** Called from {@link #init(javax.servlet.ServletConfig)}. HttpClient offers many opportunities
   * for customization. By default, SystemDefaultHttpClient is used available, otherwise it falls
   * back to:
   * <pre>new DefaultHttpClient(new ThreadSafeClientConnManager(),hcParams)</pre>*/
  @SuppressWarnings({"unchecked", "deprecation"})
  protected HttpClient createHttpClient(HttpParams hcParams) {
    try {
      //as of HttpComponents v4.2, this class is better since it uses System
      // Properties:
      Class clientClazz = Class.forName("org.apache.http.impl.client.SystemDefaultHttpClient");
      Constructor constructor = clientClazz.getConstructor(HttpParams.class);
      return (HttpClient) constructor.newInstance(hcParams);
    } catch (ClassNotFoundException e) {
      //no problem; use v4.1 below
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    //Fallback on using older client:
    return new DefaultHttpClient(new ThreadSafeClientConnManager(), hcParams);
  }

  protected void readConfigParam(HttpParams hcParams, String hcParamName, Class type) {
    String val_str = getServletConfig().getInitParameter(hcParamName);
    if (val_str == null)
      return;
    Object val_obj;
    if (type == String.class) {
      val_obj = val_str;
    } else {
      try {
        //noinspection unchecked
        val_obj = type.getMethod("valueOf",String.class).invoke(type,val_str);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    hcParams.setParameter(hcParamName,val_obj);
  }

  @Override
  public void destroy() {
    //As of HttpComponents v4.3, clients implement closeable
    if (proxyClient instanceof Closeable) {//TODO AutoCloseable in Java 1.6
      try {
        ((Closeable) proxyClient).close();
      } catch (IOException e) {
        log("While destroying servlet, shutting down httpclient: "+e, e);
      }
    } else {
      //Older releases require we do this:
      if (proxyClient != null)
        proxyClient.getConnectionManager().shutdown();
    }
    super.destroy();
  }

  @Override
  protected void service(HttpServletRequest servletRequest, HttpServletResponse servletResponse)
      throws ServletException, IOException {
    // Make the Request
    //note: we won't transfer the protocol version because I'm not sure it would truly be compatible
    String method = servletRequest.getMethod();
    
    String targetUriThisRequest;
    URI targetUriObjThisRequest;
    List<String> proxyArgList = null;
    if (targetUriObj != null) {
      targetUriObjThisRequest = targetUriObj;
      targetUriThisRequest = targetUri;
    } else {
      Matcher args = URL_ARG_PATTERN.matcher(targetUri);
      Map<String,String> params = null;
      proxyArgList = new ArrayList<String>();
      StringBuffer sb = new StringBuffer();
      while (args.find()) {
        
        /**
         * Do not use servletRequest.getParameter(arg) because that will
         * typically read and consume the servlet InputStream (where our
         * form data is stored for POST). We need the InputStream later on.
         * So we'll parse the query string ourselves. A side benefit is
         * we can keep the proxy parameters in the query string and not
         * have to add them to a URL encoded form attachment.
         */
        if (params == null) {
          String queryString = "?"+servletRequest.getQueryString();
          int hash = queryString.indexOf('#');
          if (hash >= 0) {
            queryString = queryString.substring(0, hash);
          }
          List<NameValuePair> pairs;
          try {
            pairs = URLEncodedUtils.parse(new URI(queryString), UTF_8);
          } catch (URISyntaxException e) {
            throw new RuntimeException("Unexpected URI parsing error on "+queryString, e);
          }
          params = new HashMap<String,String>();
          for (NameValuePair pair : pairs) {
            params.put(pair.getName(), pair.getValue());
          }
        }
        String arg = args.group();
        String replacement;
        String proxyArgName;
        if (arg.charAt(0) == '$') {
          proxyArgName = "proxyArg"+arg.charAt(1);
          replacement = params.get(proxyArgName);
        } else {
          proxyArgName = arg.substring(1, arg.length()-1)+"ProxyArg";
          replacement = params.get(proxyArgName);
            // Trying to implement a small subset of http://tools.ietf.org/html/rfc6570.
            // It might be a nice addition to have some syntax that allowed a proxy arg to be "optional", that is,
            // don't fail if not present, just return the empty string or a given default. But I don't see
            // anything in the spec that supports this kind of construct.
            // Notionally, it might look like {?host:google.com} would return the value of
            // the URL parameter "?hostProxyArg=somehost.com" if defined, but if not defined, return "google.com".
            // Similarly, {?host} could return the value of hostProxyArg or empty string if not present.
            // But that's not how the spec works. So for now we will require a proxy arg to be present
            // if defined for this proxy URL.
        }
        proxyArgList.add(proxyArgName+"="+replacement);
        if (replacement == null) {
          throw new RuntimeException("Missing HTTP paramater "+proxyArgName+" for proxy argument "+arg);
        }
        args.appendReplacement(sb, replacement);
      }
      args.appendTail(sb);
      targetUriThisRequest = sb.toString();
      try {
        targetUriObjThisRequest = new URI(targetUriThisRequest);
      } catch (Exception e) {
        throw new ServletException("Trying to process targetUri "+targetUri+" with args from request params '"+servletRequest.getQueryString()+"': "+e,e);
      }
    }
    
    String proxyRequestUri;
    if (targetUriObj == null) {
      proxyRequestUri = rewriteUrlFromRequest(servletRequest, targetUriThisRequest, proxyArgList);
    } else { // for backward compatibility with possible subclass extension
      proxyRequestUri = rewriteUrlFromRequest(servletRequest);
    }
    HttpRequest proxyRequest;
    //spec: RFC 2616, sec 4.3: either of these two headers signal that there is a message body.
    if (servletRequest.getHeader(HttpHeaders.CONTENT_LENGTH) != null ||
        servletRequest.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
      HttpEntityEnclosingRequest eProxyRequest = new BasicHttpEntityEnclosingRequest(method, proxyRequestUri);
      // Add the input entity (streamed)
      //  note: we don't bother ensuring we close the servletInputStream since the container handles it
      eProxyRequest.setEntity(new InputStreamEntity(servletRequest.getInputStream(), servletRequest.getContentLength()));
      proxyRequest = eProxyRequest;
    } else
      proxyRequest = new BasicHttpRequest(method, proxyRequestUri);

    if (targetUriObj == null) {
      copyRequestHeaders(servletRequest, proxyRequest, targetUriObjThisRequest);
    } else { // for backward compatibility with possible subclass extension
      copyRequestHeaders(servletRequest, proxyRequest);
    }
    
    setXForwardedForHeader(servletRequest, proxyRequest);

    HttpResponse proxyResponse = null;
    try {
      // Execute the request
      if (doLog) {
        log("proxy " + method + " uri: " + servletRequest.getRequestURI() + " -- " + proxyRequest.getRequestLine().getUri());
      }
      proxyResponse = proxyClient.execute(URIUtils.extractHost(targetUriObjThisRequest), proxyRequest);

      // Process the response
      int statusCode = proxyResponse.getStatusLine().getStatusCode();

      if (targetUriObj == null) {
        if (doResponseRedirectOrNotModifiedLogic(servletRequest, servletResponse, proxyResponse, statusCode, targetUriThisRequest)) {
          //the response is already "committed" now without any body to send
          //TODO copy response headers?
          return;
        }
      } else { // for backward compatibility with possible subclass extension
        if (doResponseRedirectOrNotModifiedLogic(servletRequest, servletResponse, proxyResponse, statusCode)) {
          //the response is already "committed" now without any body to send
          //TODO copy response headers?
          return;
        }
      }
  
      // Pass the response code. This method with the "reason phrase" is deprecated but it's the only way to pass the
      //  reason along too.
      //noinspection deprecation
      servletResponse.setStatus(statusCode, proxyResponse.getStatusLine().getReasonPhrase());

      copyResponseHeaders(proxyResponse, servletResponse);

      // Send the content to the client
      copyResponseEntity(proxyResponse, servletResponse);

    } catch (Exception e) {
      //abort request, according to best practice with HttpClient
      if (proxyRequest instanceof AbortableHttpRequest) {
        AbortableHttpRequest abortableHttpRequest = (AbortableHttpRequest) proxyRequest;
        abortableHttpRequest.abort();
      }
      if (e instanceof RuntimeException)
        throw (RuntimeException)e;
      if (e instanceof ServletException)
        throw (ServletException)e;
      //noinspection ConstantConditions
      if (e instanceof IOException)
        throw (IOException) e;
      throw new RuntimeException(e);

    } finally {
      // make sure the entire entity was consumed, so the connection is released
      if (proxyResponse != null)
        consumeQuietly(proxyResponse.getEntity());
      //Note: Don't need to close servlet outputStream:
      // http://stackoverflow.com/questions/1159168/should-one-call-close-on-httpservletresponse-getoutputstream-getwriter
    }
  }

  protected boolean doResponseRedirectOrNotModifiedLogic(
          HttpServletRequest servletRequest, HttpServletResponse servletResponse,
          HttpResponse proxyResponse, int statusCode)
          throws ServletException, IOException {
    return doResponseRedirectOrNotModifiedLogic(servletRequest, servletResponse, proxyResponse, statusCode, targetUri);
  }

  protected boolean doResponseRedirectOrNotModifiedLogic(
          HttpServletRequest servletRequest, HttpServletResponse servletResponse,
          HttpResponse proxyResponse, int statusCode, String targetUriThisRequest)
          throws ServletException, IOException {
    // Check if the proxy response is a redirect
    // The following code is adapted from org.tigris.noodle.filters.CheckForRedirect
    if (statusCode >= HttpServletResponse.SC_MULTIPLE_CHOICES /* 300 */
        && statusCode < HttpServletResponse.SC_NOT_MODIFIED /* 304 */) {
      Header locationHeader = proxyResponse.getLastHeader(HttpHeaders.LOCATION);
      if (locationHeader == null) {
        throw new ServletException("Received status code: " + statusCode
            + " but no " + HttpHeaders.LOCATION + " header was found in the response");
      }
      // Modify the redirect to go to this proxy servlet rather that the proxied host
      String locStr = rewriteUrlFromResponse(servletRequest, locationHeader.getValue(), targetUriThisRequest);

      servletResponse.sendRedirect(locStr);
      return true;
    }
    // 304 needs special handling.  See:
    // http://www.ics.uci.edu/pub/ietf/http/rfc1945.html#Code304
    // We get a 304 whenever passed an 'If-Modified-Since'
    // header and the data on disk has not changed; server
    // responds w/ a 304 saying I'm not going to send the
    // body because the file has not changed.
    if (statusCode == HttpServletResponse.SC_NOT_MODIFIED) {
      servletResponse.setIntHeader(HttpHeaders.CONTENT_LENGTH, 0);
      servletResponse.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
      return true;
    }
    return false;
  }

  protected void closeQuietly(Closeable closeable) {
    try {
      closeable.close();
    } catch (IOException e) {
      log(e.getMessage(), e);
    }
  }

  /** HttpClient v4.1 doesn't have the
   * {@link org.apache.http.util.EntityUtils#consumeQuietly(org.apache.http.HttpEntity)} method. */
  protected void consumeQuietly(HttpEntity entity) {
    try {
      EntityUtils.consume(entity);
    } catch (IOException e) {//ignore
      log(e.getMessage(), e);
    }
  }

  /** These are the "hop-by-hop" headers that should not be copied.
   * http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html
   * I use an HttpClient HeaderGroup class instead of Set<String> because this
   * approach does case insensitive lookup faster.
   */
  protected static final HeaderGroup hopByHopHeaders;
  static {
    hopByHopHeaders = new HeaderGroup();
    String[] headers = new String[] {
        "Connection", "Keep-Alive", "Proxy-Authenticate", "Proxy-Authorization",
        "TE", "Trailers", "Transfer-Encoding", "Upgrade" };
    for (String header : headers) {
      hopByHopHeaders.addHeader(new BasicHeader(header, null));
    }
  }

  protected void copyRequestHeaders(HttpServletRequest servletRequest, HttpRequest proxyRequest) {
    copyRequestHeaders(servletRequest, proxyRequest, targetUriObj);
  }
  
  /** Copy request headers from the servlet client to the proxy request. */
  protected void copyRequestHeaders(HttpServletRequest servletRequest, HttpRequest proxyRequest, URI targetUriObjThisRequest) {
    // Get an Enumeration of all of the header names sent by the client
    Enumeration enumerationOfHeaderNames = servletRequest.getHeaderNames();
    while (enumerationOfHeaderNames.hasMoreElements()) {
      String headerName = (String) enumerationOfHeaderNames.nextElement();
      //Instead the content-length is effectively set via InputStreamEntity
      if (headerName.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH))
        continue;
      if (hopByHopHeaders.containsHeader(headerName))
        continue;

      Enumeration headers = servletRequest.getHeaders(headerName);
      while (headers.hasMoreElements()) {//sometimes more than one value
        String headerValue = (String) headers.nextElement();
        // In case the proxy host is running multiple virtual servers,
        // rewrite the Host header to ensure that we get content from
        // the correct virtual server
        if (headerName.equalsIgnoreCase(HttpHeaders.HOST)) {
          HttpHost host = URIUtils.extractHost(targetUriObjThisRequest);
          headerValue = host.getHostName();
          if (host.getPort() != -1)
            headerValue += ":"+host.getPort();
        }
        proxyRequest.addHeader(headerName, headerValue);
      }
    }
  }

  private void setXForwardedForHeader(HttpServletRequest servletRequest,
                                      HttpRequest proxyRequest) {
    String headerName = "X-Forwarded-For";
    if (doForwardIP) {
      String newHeader = servletRequest.getRemoteAddr();
      String existingHeader = servletRequest.getHeader(headerName);
      if (existingHeader != null) {
        newHeader = existingHeader + ", " + newHeader;
      }
      proxyRequest.setHeader(headerName, newHeader);
    }
  }

  /** Copy proxied response headers back to the servlet client. */
  protected void copyResponseHeaders(HttpResponse proxyResponse, HttpServletResponse servletResponse) {
    for (Header header : proxyResponse.getAllHeaders()) {
      if (hopByHopHeaders.containsHeader(header.getName()))
        continue;
      servletResponse.addHeader(header.getName(), header.getValue());
    }
  }

  /** Copy response body data (the entity) from the proxy to the servlet client. */
  protected void copyResponseEntity(HttpResponse proxyResponse, HttpServletResponse servletResponse) throws IOException {
    HttpEntity entity = proxyResponse.getEntity();
    if (entity != null) {
      OutputStream servletOutputStream = servletResponse.getOutputStream();
      entity.writeTo(servletOutputStream);
    }
  }

  protected String rewriteUrlFromRequest(HttpServletRequest servletRequest) {
    return rewriteUrlFromRequest(servletRequest, targetUri, null);
  }
  
  /** Reads the request URI from {@code servletRequest} and rewrites it, considering {@link
   * #gargetUriObi}. It's used to make the new request.
   */
  protected String rewriteUrlFromRequest(HttpServletRequest servletRequest,
      String targetUriThisRequest, List<String> proxyArgList) {
    StringBuilder uri = new StringBuilder(500);
    uri.append(targetUriThisRequest);
    // Handle the path given to the servlet
    if (servletRequest.getPathInfo() != null) {//ex: /my/path.html
      uri.append(encodeUriQuery(servletRequest.getPathInfo()));
    }
    // Handle the query string
    String queryString = servletRequest.getQueryString();//ex:(following '?'): name=value&foo=bar#fragment
    if (queryString != null && queryString.length() > 0) {
      int fragIdx = queryString.indexOf('#');
      String queryNoFrag = (fragIdx < 0 ? queryString : queryString.substring(0,fragIdx));
      if (proxyArgList != null) {
        for (String proxyArg : proxyArgList) {
          queryNoFrag = queryNoFrag.replaceFirst("[&]*"+proxyArg, "");
        }
      }
      if (queryNoFrag.length() > 0) {
        uri.append('?');
        uri.append(encodeUriQuery(queryNoFrag));
      }
      if (doSendUrlFragment && fragIdx >= 0) {
        uri.append('#');
        uri.append(encodeUriQuery(queryString.substring(fragIdx + 1)));
      }
    }
    return uri.toString();
  }
  
  protected String rewriteUrlFromResponse(HttpServletRequest servletRequest, String theUrl) {
    return rewriteUrlFromResponse(servletRequest, theUrl, targetUri);
  }

  /** For a redirect response from the target server, this translates {@code theUrl} to redirect to
   * and translates it to one the original client can use. */
  protected String rewriteUrlFromResponse(HttpServletRequest servletRequest, String theUrl, String targetUriThisRequest) {
    //TODO document example paths
    if (theUrl.startsWith(targetUriThisRequest)) {
      String curUrl = servletRequest.getRequestURL().toString();//no query
      String pathInfo = servletRequest.getPathInfo();
      if (pathInfo != null) {
        assert curUrl.endsWith(pathInfo);
        curUrl = curUrl.substring(0,curUrl.length()-pathInfo.length());//take pathInfo off
      }
      theUrl = curUrl+theUrl.substring(targetUriThisRequest.length());
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
   * @param in example: name=value&foo=bar#fragment
   */
  protected static CharSequence encodeUriQuery(CharSequence in) {
    //Note that I can't simply use URI.java to encode because it will escape pre-existing escaped things.
    StringBuilder outBuf = null;
    Formatter formatter = null;
    for(int i = 0; i < in.length(); i++) {
      char c = in.charAt(i);
      boolean escape = true;
      if (c < 128) {
        if (asciiQueryChars.get((int)c)) {
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
    char[] c_reserved = "?/[]@".toCharArray();//plus punct

    asciiQueryChars = new BitSet(128);
    for(char c = 'a'; c <= 'z'; c++) asciiQueryChars.set((int)c);
    for(char c = 'A'; c <= 'Z'; c++) asciiQueryChars.set((int)c);
    for(char c = '0'; c <= '9'; c++) asciiQueryChars.set((int)c);
    for(char c : c_unreserved) asciiQueryChars.set((int)c);
    for(char c : c_punct) asciiQueryChars.set((int)c);
    for(char c : c_reserved) asciiQueryChars.set((int)c);

    asciiQueryChars.set((int)'%');//leave existing percent escapes in place
  }

}
