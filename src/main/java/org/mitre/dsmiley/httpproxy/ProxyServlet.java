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

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.AbortableHttpRequest;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.client.params.CookiePolicy;
import org.apache.http.client.utils.URIUtils;
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

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.HttpCookie;
import java.net.URI;
import java.util.BitSet;
import java.util.Enumeration;
import java.util.Formatter;
import java.util.List;

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
  protected static final String P_TARGET_URI = "targetUri";
  public static final String ATTR_TARGET_URI =
          ProxyServlet.class.getSimpleName() + ".targetUri";
  public static final String ATTR_TARGET_HOST =
          ProxyServlet.class.getSimpleName() + ".targetHost";


  //These next 3 are cached here, and should only be referred to in initialization logic. See the
  // ATTR_* parameters.
  /** From the configured parameter "targetUri". */
  protected String targetUri;
  protected URI targetUriObj;//new URI(targetUri)
  protected HttpHost targetHost;//URIUtils.extractHost(targetUriObj);

  Proxy proxy;

  @Override
  public String getServletInfo() {
    return "A proxy servlet by David Smiley, dsmiley@apache.org";
  }


  protected String getTargetUri(HttpServletRequest servletRequest) {
    return (String) servletRequest.getAttribute(ATTR_TARGET_URI);
  }

  private HttpHost getTargetHost(HttpServletRequest servletRequest) {
    return (HttpHost) servletRequest.getAttribute(ATTR_TARGET_HOST);
  }

  /** The string prefixing rewritten cookies. */
  protected String getCookieNamePrefix() {
    return "!Proxy!" + getServletConfig().getServletName();
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
    proxy = new Proxy();

    initTarget();//sets target*

    HttpParams hcParams = new BasicHttpParams();
    hcParams.setParameter(ClientPNames.COOKIE_POLICY, CookiePolicy.IGNORE_COOKIES);
    readConfigParam(hcParams, ClientPNames.HANDLE_REDIRECTS, Boolean.class);
    proxy.buildProxyClient(hcParams);

    String doForwardIPString = getConfigParam(P_FORWARDEDFOR);
    if (doForwardIPString != null) {
      proxy.setDoForwardIP(Boolean.parseBoolean(doForwardIPString));
    }

    String doLogStr = getConfigParam(P_LOG);
    if (doLogStr != null) {
      proxy.setLogger(new Logger() {
        @Override
        public void logMessage(String s, Exception e) {
          log(s, e);
        }

        @Override
        public void logMessage(String s) {
          log(s);
        }
      });
    }
    proxy.setCookieNamePrefix(getCookieNamePrefix());
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
    targetHost = URIUtils.extractHost(targetUriObj);
  }

  /** The http client used.
   * @see #proxy.createHttpClient(HttpParams) */
  protected HttpClient getProxyClient() {
    return proxy.getProxyClient();
  }

  /** Reads a servlet config parameter by the name {@code hcParamName} of type {@code type}, and
   * set it in {@code hcParams}.
   */
  protected void readConfigParam(HttpParams hcParams, String hcParamName, Class type) {
    String val_str = getConfigParam(hcParamName);
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
    proxy.destroy();
    proxy = null;
    super.destroy();
  }

  @Override
  protected void service(HttpServletRequest servletRequest, HttpServletResponse servletResponse)
      throws ServletException, IOException {
    //initialize request attributes from caches if unset by a subclass by this point
    String _targetUri = (String)servletRequest.getAttribute(ATTR_TARGET_URI);
    if (_targetUri == null) {
      _targetUri = targetUri;
    }
    HttpHost _targetHost = (HttpHost)servletRequest.getAttribute(ATTR_TARGET_HOST);
    if (_targetHost == null) {
      _targetHost = targetHost;
    }

    proxy.forward(servletRequest, servletResponse, _targetUri, _targetHost);
  }


  /** The target URI as configured. Not null. */
  public String getTargetUri() { return targetUri; }

}
